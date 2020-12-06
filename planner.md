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

In RDF stores, there's no explicit identification of entities, and a Subject vs. Object join is implicit by value unification, whereas in EAV stores, these are explicit.  First, the entities are explicitly defined during transactions in EAV stores; second, the entity vs. entity relationship is also explicitly marked by `:ref` value type. Concequently, unlike in RDF stores, expensive algorithms to discover entities (e.g. characteristic sets [7])  and their relationship (e.g. extended characteristic sets [4]) can be made cheaper in order to exploit these for indexing. 

On the other hand, RDF stores often have a very limited number of properties even for huge datasets, whereas EAV stores often have more attributes due to encoding the entity information in the namespaces of the attributes. 

In RDF stores, all the triple values are normally considered norminal, and hence reprerentable by integer IDs, whereas in EAV stores, the values of V may be considered ordinal (i.e. as numerical data), hence it is beneficial to store them as they are and sort them by value, in order to facilitate range predicates.  

## Entity class

In order to leverage the semantics of EAV stores more effectively, we introduce a concept of entity class, which refers to the type of the entity. Similar to characteristic sets [7] in RDF stores, this concept captures the combination of attributes for a class of entities. In EAV stores, the set of attributes for a class of entities are often unique. There might be overlapping attributes between entity classes, but many attributes are used by only one class of entities, and these are often prefixed by the namespace unique to that entity class. We assign auto increment integer id for each entity class. and represent the mapping from attributes to entity class with bitmaps. Specifically, each attribute map entry has a `:entity-classes` key pointing to a roaring bitmap containing the ids of the entity classes that include the attribute in their definitions. This way, we can quickly identify the entity classes relevant to a query through fast bitmap AND.

The mapping of entity class to corresponding entities is represented by a map of entity class ids to a roaing bitmap of entity ids. Such a map will be stored in a "class" LMDB BDI, so we can quickly identfy the entities of a class. 

## Indexing and statistics collection

As mentioned, the unique properties of EAV stores allows us to build the entiy classes indices above incrementally during data transactions, instead of having to build them using expensive algorithms to go through full data out of band. 

The planner will use data statistics to estimate cost. Some of the statistics
will be updated during transaction, e.g. min, max, total count, cardinality, while
others are collected at query time, e.g. range scan count and filter predicate
count, since these are relatively cheap to query.


## Query plan generation and execution

It should be obvious by now that the optimzer will mainly leverage the entity classes and the links between them to generate the execution plan. 


First, the planner will rewrite the query to push down predicate clauses into predicate filtering on indices directly, if possible.

It then identifies the entity classes as well as their linkage involved in the query.  

The joins within a  entity class is a star join, and the links between them are the chain joins. 

The star joins can be effectively handled with simple heuristics of starting with joins of least cardinaltiy (e.g. [1], [4]). These star joins can use efficent merge joins as they can leverage results from index scans that are already sorted. The star joins can be done in parallel. The partial results of one join can be shared and facilitate filtering of other joins (i.e. the so called side-way informatioin passing [6]).

Instead of using the expensive dynamic programming technique to find the join order for chain joins, we use simple cost estimation methods and simple heuristics in [4] to find the order with minimal cost. 


* The planner will maintain the so called interesting order information, so that merge join can be used as much as possible, to exploit the sorted nature of range scans on the indices. 

* Nested loop join will be used when the counts are known to be very small, so we save the overhead of building a hash table.

* Hash join will be used when the above are not true.


During the plan execution, datoms will be fetched progressively only when they
become necessary so as to ensure minimal data are fetched and processed.

The execution engine will also attempt to minimize the materialization of
intermediate relations.

## Reference

[1] Gubichev, Andrey, and Thomas Neumann. "Exploiting the query structure for efficient join ordering in SPARQL queries." EDBT. Vol. 14. 2014.

[2] Leis, Viktor, et al. "Cardinality Estimation Done Right: Index-Based Join Sampling." Cidr. 2017.

[3] Leis, Viktor, et al. "How good are query optimizers, really?." Proceedings of the VLDB Endowment 9.3 (2015): 204-215.

[4] Meimaris, Marios, et al. "Extended characteristic sets: graph indexing for SPARQL query optimization." 2017 IEEE 33rd International Conference on Data Engineering (ICDE). IEEE, 2017.

[5] Moerkotte, Guido, and Thomas Neumann. "Analysis of two existing and one new dynamic programming algorithm for the generation of optimal bushy join trees without cross products." Proceedings of the 32nd international conference on Very large data bases. 2006.

[6] Neumann, Thomas, and Gerhard Weikum. "Scalable join processing on very large RDF graphs." Proceedings of the 2009 ACM SIGMOD International Conference on Management of data. 2009.

[7] Neumann, Thomas, and Guido Moerkotte. "Characteristic sets: Accurate cardinality estimation for RDF queries with multiple joins." 2011 IEEE 27th International Conference on Data Engineering (ICDE). IEEE, 2011.


