## Query Optimizer

Right now Datalevin uses Datascript query engine, which is inefficient, as it
does things the following way: All the data that matches each *individual* triple
pattern clause are fetched and turned into a set of datoms. The sets of datoms
matching each clause are then hash-joined together one set after another. There's no query optimization.

Even with user hand crafted clauses order, this process still performs a lot of
unnecessary data fetching and transformation, and stresses the join process and
materializes a lot of intermediate relations needlessly.

To address these problems, we will develop a query optimizer to generate an
optimized query plan. A plan with minimal cost will be selected for execution. We will also provide explain function for the users to see the plan. This optimizer will leverage the unique properties of EAV stores.

## Difference from RDF Stores

Although EAV stores are heavily inspired by RDF stores, there are some important differences that impacts the optimizer design.

In RDF stores, there's no explicit identification of entities, and a Subject vs. Object join is implicit by value unification, whereas in EAV stores, these are explicit.  First, the entities are explicitly defined during transactions in EAV stores; second, the entity vs. entity relationship is also explicitly marked by `:ref` value type. Concequently, unlike in RDF stores, expensive algorithms to discover entities (e.g. characteristic sets [2])  and their relationship (e.g. extended characteristic sets [1]) can be made cheaper in order to exploit these for indexing. 

On the other hand, RDF stores often have a very limited number of properties even for huge datasets, whereas EAV stores often have more attributes due to encoding the entity information in the namespaces of the attributes. 

In RDF stores, all the triple values are normally considered norminal, and hence reprerentable by integer IDs, whereas in EAV stores, the values of V may be considered ordinal (i.e. as numerical data), hence it is beneficial to store them as they are and sort them by value, in order to facilitate range predicates.  

## Entity classes

In order to leverage the semantics of EAV stores more effectively, we introduce a concept of entity class, which refers to the type of the entity. Similar to characteristic sets [2] in RDF stores or tables in relational DB, this concept captures the combination of attributes for a class of entities. In EAV stores, the set of attributes for a class of entities are often unique. There might be overlapping attributes between entity classes, but many attributes are used by only one class of entities, and these are often prefixed by the namespace unique to that entity class. We assign auto increment integer id for each entity class, and represent the mapping from attributes to entity class with bitmaps. 

Specifically, each attribute schema entry has a `:db/classes` key pointing to a set of entity class ids that include the attribute in their definitions. This way, we can quickly identify the entity classes relevant to a query or a transaction through fast set intersections.

An additional "classes" LMDB DBI will be used, the keys will be class ids, and the values are the corresponding entities represented by a roaing bitmap of entity ids. This allows us to quickly find relevant entities for a query.

For common attributes that should not contribute to the defintion of entity classes, user can mark such attributes under `:common-attributes` key, as part of of the option map given when openning the DB. 

## Class links

We also introduce a notion of entity class link that represents a pair of entity classes that are connected by references. Similar to extended characteristic sets [1] or foreigin key relation in relational DB, this concept captures the long range relationship in the EAV data, which is often declared in the schema as a `:db.type/ref` value type. We will leverage such declaration to infer the links between entity classes, and store the resulting graph.

Specifically, a "links" LMDB DBI will be used, the keys are the pair of the entity class ids of the referring entity class (class of E) and the referred entity class (class of V), the values are roaring bitmaps of entity ids of referred entities (V), so that we can look up the linking triples quickly in VAE DBI.

In addition, the scheam map has a built-in key `:db/graph`, and its value stores the adjacency list of the class link graph of the data, i.e. a map of links to the set of their adjacent links.

## Statistics collection

As mentioned, the unique properties of EAV stores allows us to build the entiy classes and links above incrementally during data transactions, instead of having to build them using expensive algorithms to go through full data out of band. 

The planner will use data statistics to estimate cost. Some of the statistics
will be updated during transaction. They are effectively collected when we build the various roaring bitmaps, as these bitmaps can easily return the distinct counts and other statisticss. Others are collected at query time, e.g. range scan count and filter predicate count, since these are relatively cheap to query. 

## Query plan generation and execution

The optimzer will mainly leverage the entity classes and the links between them to generate the execution plan. 

Entity classes and links form a directed graph. Querying is effectively matching query graph and data graph.  First the planner identifies the entity classes as well as their linkage in the query.  Query link graph is then matched to data link graph, producing a set of link chains. Each chain can be joined in parallel, where the triples of the matched links can be joined to produce the results. The results of the chains then hash-joined together. The plan decides the join order of a chain based on estimated cost. 

The joins within a entity class is a star join, and the links between them are the chain joins. The planner is driven by the chain joins and does not consider star joins. The reason to avoid starting with star joins is because they are often unselective due to high correlation between attributes of an entity.  The star joins will be performed while retrieving the triples of a link during plan execution through semi joins or merge joins with the link triples.  Instead of using the expensive dynamic programming technique to find the join order for chain joins, we use simple cost estimation methods and simple heuristics in [1] to find the order with minimal cost.  Basically, we will join links with increasing cardinaltiy. 

In general, these are the principle of choosing join method:

* The planner will maintain the so called interesting order information, so that merge join can be used as much as possible, e.g. to exploit the sorted nature of range scans on the indices. 

* Nested loop join will be used when the counts are known to be very small, so we save the overhead of building a hash table.

* Hash join will be used when the above are not true.

During the plan execution, datoms will be fetched progressively only when they
become necessary so as to ensure minimal data are fetched and processed.

Finally, the planner will rewrite the query to push down predicate clauses into predicate filtering on indices directly, if possible. If not, will order them so they are performed as early as possbile to minimize unnecessary intermediate results.


## Reference

[1] Meimaris, Marios, et al. "Extended characteristic sets: graph indexing for SPARQL query optimization." 2017 IEEE 33rd International Conference on Data Engineering (ICDE). IEEE, 2017.

[2] Neumann, Thomas, and Guido Moerkotte. "Characteristic sets: Accurate cardinality estimation for RDF queries with multiple joins." 2011 IEEE 27th International Conference on Data Engineering (ICDE). IEEE, 2011.


