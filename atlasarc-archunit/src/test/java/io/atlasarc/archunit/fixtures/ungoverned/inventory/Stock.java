package io.atlasarc.archunit.fixtures.ungoverned.inventory;

import io.atlasarc.archunit.fixtures.ungoverned.catalog.Product;

public class Stock {
    public Product product() {
        return new Product();
    }
}
