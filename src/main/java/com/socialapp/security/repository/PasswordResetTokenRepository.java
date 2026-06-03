package com.socialapp.security.repository;

import com.socialapp.security.entity.PasswordResetToken;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PasswordResetTokenRepository extends JpaRepository<PasswordResetToken, String> {

  void deleteByUserId(Integer userId);
}
