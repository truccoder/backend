package com.socialapp.search.service;

import java.util.List;

import org.springframework.stereotype.Service;

import com.socialapp.friendships.repository.FriendshipRepository;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class FriendshipQueryService {
  private final FriendshipRepository friendshipRepository;

  public List<Integer> getFriendIds(Integer userId) {
    return friendshipRepository.findFriendIds(userId);
  }
}
