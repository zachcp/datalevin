# Datalevin Query Planner

## Motivation

Currently, Datalevin mostly inherits Datascript query engine, which is inefficient, as it
does things the following way: all the data that matches each *individual* triple
pattern clause are fetched and turned into a set of datoms. The sets of datoms
matching each clause are then hash-joined together one set after another.

Even with user hand crafted clauses order, this process still performs a lot of
unnecessary data fetching and transformation, joins a lot of unneeded tuples and
materializes a lot of intermediate relations needlessly. Although Datalevin
added a few simple query optimizations, it is far from solving the problems.

To address these problems, we will develop a new query engine with a query
planner. We will also support a `explain` function for the users to see the query
plan. This query planner will leverage some unique properties of EAV stores,
take advantage of some new indexing structures, and do concurrent query execution.

## Difference from RDF Stores

Although EAV stores are heavily inspired by RDF stores, there are some important
differences that impacts the query engine design.

In RDF stores, there's no explicit identification of entities, and a Subject vs.
Object join is implicit by value unification, whereas in EAV stores, these are
explicit.  First, the entities are explicitly defined during transactions in EAV
stores; second, the entity vs. entity relationship is also explicitly marked by
`:db.type/ref` value type. Concequently, unlike in RDF stores, expensive
algorithms to discover entities (e.g. characteristic sets [2])  and their
relationship (e.g. extended characteristic sets [1]) can be made cheaper. We
will exploit these for indexing.

On the other hand, RDF stores often have a very limited number of properties
even for huge datasets, whereas EAV stores can have many more attributes as
attributes are often specialized, e.g. to encode the entity information in the
namespaces of the attributes. Grouping of attributes should have greater benefits.

In RDF stores, all the triple values are normally considered nominal literals, and hence
encoded as integer IDs, whereas in EAV stores, like in relational databases, the
values often should be considered ordinal (i.e. as numerical data), hence it is
beneficial to store them as they are and sort them by value. It is important to push down
predicates on values into indexing access methods in order to take advantage of
this kind of storage.

## Entity classes

In order to leverage the semantics of EAV stores more effectively, we introduce
a concept of entity class, which refers to the type of the entity. Similar to
characteristic sets [2] in RDF stores or tables in relational DB, this concept
captures the unique combination of attributes for a class of entities.

In EAV stores, the set of attributes for a class of entities are often unique.
There might be overlapping attributes between entity classes, but many
attributes are used by only one class of entities, and these are often prefixed
by the namespace unique to that entity class. We assign auto increment integer
id for each entity class, and represent the mapping from attributes to entity
class with bitmaps.

Specifically, each attribute schema entry has a `:db/classes` key pointing to a
set of entity class ids that include the attribute in their definitions. This
way, we can quickly identify the entity classes relevant to a query or a
transaction through fast set intersections.

An additional "classes" LMDB DBI will be used, the keys will be class ids, and
the values are the corresponding entities represented by a bitmap of
entity ids. This allows us to quickly find relevant entities for a query.

For common attributes that should not contribute to the defintion of entity
classes, user can mark such attributes under `:common-attributes` key, as part
of of the option map given when openning the DB.

## Class links

We also introduce a notion of entity class link that represents a pair of entity
classes that are connected by triples with `:db.type/ref` type attributes.
Similar to extended characteristic sets [1] or foreigin key relation in
relational DB, this concept captures the long range relationship in the EAV
data. We will leverage such declaration to infer the links between entity
classes in the data, and store the resulting graph.

Specifically, a "links" LMDB DBI will be used, the keys are the pair of the
entity class ids of the referring entity class (class of E) and the referred
entity class (class of V), the values are bitmaps of entity ids of
referred entities (V), so that we can look up the linking triples quickly in VAE
DBI, which contains linking triples only.

In addition, the scheam map has a built-in key `:db/graph`, and its value stores
the adjacency list of the class link graph of the data, i.e. a map of links to
the set of their adjacent links.

## Statistics collection

As mentioned, the unique properties of EAV stores allows us to build the entity
classes and links described above incrementally during data transactions, instead of
having to build them using expensive algorithms to go through full data set.

The planner will use data statistics to estimate cost of operations. Some of the statistics
will be updated during transaction. They are effectively collected when we build
the various bitmaps described above, as these bitmaps can easily return the distinct
counts and other statistics. Others are collected at query time, e.g. range scan count and filter
predicate count, since these are relatively cheap to query.

## Query planning

The optimzer will first leverage the entity classes and the links between them
to generate the skeleton of the plan.  Essentially, we leverage the set of attributes
and their relationships in the query to:

  a. break up the query into sub-queries that can be independently executed,
  b. pre-filter the entities involved in each sub-query.

This pre-filtering significantly reduces the amount of work we have to do, especially for
complex queries that involves long chains of where clauses.

As we can see, the planner first consider long range relationships.  The reason to avoid
starting with local star-like relationships is because they are often
unselective, due to high correlation between attributes of an entity class.

Entity classes and links form a directed graph. Querying is effectively matching
query graph with the stored data graph.  After the planner identifies the entity classes as
well as their linkage in the query, query link graph is then matched to data
link graph, producing a set of link chains.

Each chain can be considered a sub-query, and sub-queries can be processed in parallel. The results of the
sub-queries then hash-joined together.

For joins within a sub-query, we use the heuristics of join with increasing cardinality.
Obviously, patterns with bound values and smaller results are joined first.
 Attributes values can be obtained by joining entities looked up in "classes"
 with entities looked up in "links". "classes" and "links" lookups participate
 in the join order calculation as if they are normal patterns, as they may not be the
 cheapest. This addresses the limitation of [1], which does not have other indices.

Instead of using traditional yet expensive dynamic programming techniques to find the join
order for joining sub-queries results, we use simple cost estimation methods and simple
heuristics in [1] to find the order with minimal cost. Basically, we will join
chains with increasing cardinality.

Finally, the planner will rewrite the query to push down predicate clauses into
predicate filtering on indices directly to minimize unnecessary intermediate results.

## Join method

These are the principle of choosing join method:

* The planner will maintain the so called interesting order information, so that
  merge join can be used as much as possible,  e.g. to exploit the sorted nature
  of range scans on the indices.

* Nested loop join will be used when the counts are known to be very small, so
  we save the overhead of building a hash table.

* Hash join will be used when the above are not true.

## Query execution

During the plan execution, data will be fetched progressively only when they
become necessary so as to ensure minimal data are fetched and processed.

Instead of returning a list of full datoms, the query index access functions should
return a list of bound values of variables instead. This would avoid repetitively
deserializing known components of a triple pattern, and then have to ignore them
during joins. Such results are also consistent with the results of bitmap operations on
"classes" and "links" indices, i.e. they return a list of sorted entity ids.

As mentioned, each sub-query can be executed by different thread in parallel, so
large query can be performed more quickly on a multi-core system.

## Reference

[1] Meimaris, Marios, et al. "Extended characteristic sets: graph indexing for SPARQL query optimization." 2017 IEEE 33rd International Conference on Data Engineering (ICDE). IEEE, 2017.

[2] Neumann, Thomas, and Guido Moerkotte. "Characteristic sets: Accurate cardinality estimation for RDF queries with multiple joins." 2011 IEEE 27th International Conference on Data Engineering (ICDE). IEEE, 2011.
