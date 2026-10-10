package ru.cineflow.booking.service;

/** Место освободилось (истекло удержание, отмена, возврат) — его может забрать лист ожидания. */
public record SeatFreedEvent(Long sessionId, int row, int seat, String previousOwner) {}
