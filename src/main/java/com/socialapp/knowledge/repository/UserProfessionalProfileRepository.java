package com.socialapp.knowledge.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.socialapp.knowledge.entity.UserProfessionalProfileEntity;

public interface UserProfessionalProfileRepository
    extends JpaRepository<UserProfessionalProfileEntity, Integer> {}
