package ru.cineflow.pricing.service;

import org.springframework.data.jpa.repository.JpaRepository;
import ru.cineflow.pricing.model.PromoCode;

public interface PromoRepository extends JpaRepository<PromoCode, String> {}
