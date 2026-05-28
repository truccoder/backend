package com.socialapp.friendships.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.socialapp.common.exception.ForbiddenException;
import com.socialapp.common.exception.NotFoundException;
import com.socialapp.common.exception.ValidationException;
import com.socialapp.friendships.entity.FriendRequestEntity;
import com.socialapp.friendships.entity.enums.FriendRequestStatus;
import com.socialapp.friendships.repository.FriendRequestRepository;
import com.socialapp.friendships.repository.FriendshipRepository;
import com.socialapp.security.repository.UserRepository;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class FriendshipService {
  private final FriendRequestRepository friendRequestRepository;
  private final FriendshipRepository friendshipRepository;
  private final UserRepository userRepository;

  @Transactional
  public void sendFriendRequest(Integer actorId, Integer addresseeId) {
    if (actorId.equals(addresseeId)) {
      throw new ValidationException("You cannot send a friend request to yourself");
    }
    if (!userRepository.existsById(Long.valueOf(addresseeId))) {
      throw new NotFoundException("User not found with ID: " + addresseeId);
    }
    if (friendshipRepository.areFriends(actorId, addresseeId)) {
      throw new ValidationException("You are already friends with this user");
    }
    if (friendRequestRepository.hasPendingRequestBetween(actorId, addresseeId)) {
      throw new ValidationException("A pending friend request already exists between these users");
    }

    FriendRequestEntity entity = new FriendRequestEntity();
    entity.setRequesterId(actorId);
    entity.setAddresseeId(addresseeId);
    entity.setStatus(FriendRequestStatus.PENDING);
    friendRequestRepository.save(entity);
  }

  @Transactional
  public void cancelFriendRequest(Integer actorId, Integer requestId) {
    FriendRequestEntity request = findPendingRequestOrThrow(requestId);

    if (!request.getRequesterId().equals(actorId)) {
      throw new ForbiddenException("Only the requester can cancel this friend request");
    }

    request.setStatus(FriendRequestStatus.CANCELLED);
    friendRequestRepository.save(request);
  }

  @Transactional
  public void acceptFriendRequest(Integer actorId, Integer requestId) {
    FriendRequestEntity request = findPendingRequestOrThrow(requestId);

    if (!request.getAddresseeId().equals(actorId)) {
      throw new ForbiddenException("Only the addressee can accept this friend request");
    }

    request.setStatus(FriendRequestStatus.ACCEPTED);
    friendRequestRepository.save(request);

    friendshipRepository.mergeUser(request.getRequesterId());
    friendshipRepository.mergeUser(request.getAddresseeId());
    friendshipRepository.createFriendship(request.getRequesterId(), request.getAddresseeId());
  }

  @Transactional
  public void rejectFriendRequest(Integer actorId, Integer requestId) {
    FriendRequestEntity request = findPendingRequestOrThrow(requestId);

    if (!request.getAddresseeId().equals(actorId)) {
      throw new ForbiddenException("Only the addressee can reject this friend request");
    }

    request.setStatus(FriendRequestStatus.REJECTED);
    friendRequestRepository.save(request);
  }

  private FriendRequestEntity findPendingRequestOrThrow(Integer requestId) {
    FriendRequestEntity request =
        friendRequestRepository
            .findById(requestId)
            .orElseThrow(
                () -> new NotFoundException("Friend request not found with ID: " + requestId));

    if (request.getStatus() != FriendRequestStatus.PENDING) {
      throw new ValidationException(
          "Friend request is no longer pending (current status: " + request.getStatus() + ")");
    }
    return request;
  }
}
