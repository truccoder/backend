package com.socialapp.security.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.socialapp.security.entity.PasswordResetToken;

public interface PasswordResetTokenRepository extends JpaRepository<PasswordResetToken, String> {

  void deleteByUserId(Integer userId);
}
