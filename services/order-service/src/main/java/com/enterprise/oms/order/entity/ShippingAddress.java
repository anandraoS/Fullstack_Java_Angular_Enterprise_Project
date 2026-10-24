package com.enterprise.oms.order.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

@Embeddable
public class ShippingAddress {

    @Column(name = "ship_line1", nullable = false, length = 200)
    private String line1;
    @Column(name = "ship_city", nullable = false, length = 100)
    private String city;
    @Column(name = "ship_postal_code", nullable = false, length = 20)
    private String postalCode;
    @Column(name = "ship_country", nullable = false, length = 64)
    private String country;

    protected ShippingAddress() {
    }

    public ShippingAddress(String line1, String city, String postalCode, String country) {
        this.line1 = line1;
        this.city = city;
        this.postalCode = postalCode;
        this.country = country;
    }

    public String getLine1() { return line1; }
    public String getCity() { return city; }
    public String getPostalCode() { return postalCode; }
    public String getCountry() { return country; }
}
