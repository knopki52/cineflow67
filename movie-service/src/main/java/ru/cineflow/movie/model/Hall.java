package ru.cineflow.movie.model;

import jakarta.persistence.*;

@Entity
@Table(name = "halls")
public class Hall {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;
    public Long cinemaId;
    public String name;
    public String type;
    @Column(name = "row_count")
    public int rows;
    public int seatsPerRow;
    public int priceExtra;

    public Hall() {}
    public Hall(Long cinemaId, String name, String type, int rows, int seatsPerRow, int priceExtra) {
        this.cinemaId = cinemaId; this.name = name; this.type = type;
        this.rows = rows; this.seatsPerRow = seatsPerRow; this.priceExtra = priceExtra;
    }
}
