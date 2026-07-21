# Multi-module split-package governance regression fixture

This corpus is the product-owned regression counterpart of the manual Maven acceptance repository
at `C:\workstation\atlasarc-cycle-governance-acceptance`.

It deliberately contains:

- an `orders` cycle and a `billing` cycle between the same
  `acceptance.shared.left` and `acceptance.shared.right` package names;
- an independent `reporting` cycle;
- empty, orders-plus-reporting, and fully governed repository states;
- a module-qualified repository-scope state that excludes only Billing's copy of a split package;
- evaluator configuration with stable `orders`, `billing`, and `reporting` labels.

The evaluator and ArchUnit adapter tests compile these source trees independently and run every
governance state through production acquisition and matching. The partial state is the critical
regression: the orders reference must be governed while the billing reference remains uncovered on
the same aggregated package edge.

The scope regression is the inverse disambiguation check: excluding
`billing:acceptance.shared.left` must break only the Billing cycle. The equal Orders package and its
cycle remain in the evaluated evidence in both standalone and ArchUnit paths.
