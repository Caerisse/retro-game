package com.github.retro_game.retro_game.model.repository;

import com.github.retro_game.retro_game.model.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface UserRepository extends JpaRepository<User, Long> {
  boolean existsByEmailIgnoreCase(String email);

  boolean existsByNameIgnoreCase(String name);

  Optional<User> findByEmailIgnoreCase(String email);

  Optional<User> findByNameIgnoreCase(String name);

  List<User> findByIdIn(Collection<Long> ids);
}
