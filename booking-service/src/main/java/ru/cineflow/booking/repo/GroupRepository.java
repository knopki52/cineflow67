package ru.cineflow.booking.repo;

import org.springframework.data.jpa.repository.JpaRepository;
import ru.cineflow.booking.model.GroupBooking;

public interface GroupRepository extends JpaRepository<GroupBooking, String> {}
