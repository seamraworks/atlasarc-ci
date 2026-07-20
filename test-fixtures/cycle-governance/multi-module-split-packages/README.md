# Multi-module split-package governance regression fixture

This corpus is the product-owned regression counterpart of the manual Maven acceptance repository
at `C:\workstation\atlasarc-cycle-governance-acceptance`.

It deliberately contains:

- an `orders` cycle and a `billing` cycle between the same
  `acceptance.shared.left` and `acceptance.shared.right` package names;
- an independent `reporting` cycle;
- empty, orders-plus-reporting, and fully governed repository states;
- evaluator configuration with stable `orders`, `billing`, and `reporting` labels.

The evaluator and ArchUnit adapter tests compile these source trees independently and run every
governance state through production acquisition and matching. The partial state is the critical
regression: the orders reference must be governed while the billing reference remains uncovered on
the same aggregated package edge.
