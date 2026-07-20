package io.atlasarc.archunit.fixtures.ungoverned.catalog;

import io.atlasarc.archunit.fixtures.ungoverned.inventory.Stock;

public class Product {
    public Stock stock() {
        return new Stock();
    }
}
