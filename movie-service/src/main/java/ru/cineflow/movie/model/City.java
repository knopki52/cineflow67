package ru.cineflow.movie.model;

import jakarta.persistence.*;

@Entity
@Table(name = "cities")
public class City {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;
    public String name;
    public double lat;
    public double lng;

    public City() {}
    public City(String name, double lat, double lng) { this.name = name; this.lat = lat; this.lng = lng; }
}
