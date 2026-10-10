package ru.cineflow.movie.model;

import jakarta.persistence.*;

@Entity
@Table(name = "cinemas")
public class Cinema {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;
    public Long cityId;
    public String name;
    public String address;
    public double lat;
    public double lng;

    public Cinema() {}
    public Cinema(Long cityId, String name, String address, double lat, double lng) {
        this.cityId = cityId; this.name = name; this.address = address; this.lat = lat; this.lng = lng;
    }
}
