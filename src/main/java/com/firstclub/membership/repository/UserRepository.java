package com.firstclub.membership.repository;

import com.firstclub.membership.domain.User;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserRepository extends JpaRepository<User, Long> {
}
