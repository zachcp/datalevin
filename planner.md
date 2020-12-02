# Datalevin Query Optimizer

Right now Datalevin uses Datascript query engine, which is inefficient, as it
does things the following way: All the data that matches each *individual* triple
pattern clause are fetched and turned into a set of datoms. The sets of datoms
matching each clause are then hash-joined together one set after another. Even
with user hand crafted clauses order, this process still performs a lot of
unnecessary data fetching and transformation, and stresses the join process and
materializes a lot of intermediate relations needlessly.

To address these problems, we will develop a query optimizer to generate an
optimized query plan. This will be a cost based query optimizer.  A plan with minimal cost will be selected for execution. We will also provide explain function for the users to see the plan.

The planner will use data statistics to estimate cost. Some of the statistics
will be updated during transaction, e.g. min, max, total count, cardinality, while
others are collected at query time, e.g. range scan count and filter predicate
count, since these are relatively cheap to query.

More join operations will be used:

* The planner will maintain the so called interesting order information, so that merge join can be used as long as possible, to exploit the sorted nature of range scans on the indices. 

* Nested loop join will be used when the counts are known to be very small, so we save the overhead of building a hash table.

* Hash join will be used when the above are not true.


During the plan execution, datoms will be fetched progressively only when they
become necessary so as to ensure minimal data are fetched and processed.

The execution engine will also attempt to minimize the materialization of
intermediate relations.

## Implementation

0. Query rewrite

If possible, we will push down predicate clauses into predicate filtering on indices directly. 

We will also group star shaped attributes together and use heuristics inside each group, so as to reduce the search space for dynamic programming [0].

1. Cardinality estimation 

This is the most important factor for determining optimizer quality. We will start with sampling method [1]  with a small budget (say 10ms or 1k index access), and fall back to the "characteristic set" method [5] when the sampling budget is spent.

2. Cost model

It turns out that with accurate cardinaltiy estimation, complex cost models do no better than simple cost models. So we will be using a very simple cost model as outlined in [2].

3. Plan space enumeration

We will use dynamic programming to exhaustively search for the optimal plan. Bushy plan trees will be considered. The emueration algorithm will be connected subgraph driven and bottom-up, e.g. [3]

4. Runtime

Implement ideas such as side way information passing from [4].

[0] Gubichev, Andrey, and Thomas Neumann. "Exploiting the query structure for efficient join ordering in SPARQL queries." EDBT. Vol. 14. 2014.

[1] Leis, Viktor, et al. "Cardinality Estimation Done Right: Index-Based Join Sampling." Cidr. 2017.

[2] Leis, Viktor, et al. "How good are query optimizers, really?." Proceedings of the VLDB Endowment 9.3 (2015): 204-215.

[3] Moerkotte, Guido, and Thomas Neumann. "Analysis of two existing and one new dynamic programming algorithm for the generation of optimal bushy join trees without cross products." Proceedings of the 32nd international conference on Very large data bases. 2006.

[4] Neumann, Thomas, and Gerhard Weikum. "Scalable join processing on very large RDF graphs." Proceedings of the 2009 ACM SIGMOD International Conference on Management of data. 2009.

[5] Neumann, Thomas, and Guido Moerkotte. "Characteristic sets: Accurate cardinality estimation for RDF queries with multiple joins." 2011 IEEE 27th International Conference on Data Engineering. IEEE, 2011.

