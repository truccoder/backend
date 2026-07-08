package com.socialapp.posts.controller;

import java.util.List;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.socialapp.posts.dto.LocationResolutionRequestDto;
import com.socialapp.posts.dto.LocationResolutionResponseDto;
import com.socialapp.posts.service.LocationResolutionService;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/v1/api/posts/locations")
@RequiredArgsConstructor
public class LocationController {
  private final LocationResolutionService locationResolutionService;

  @PostMapping("/resolve")
  public List<LocationResolutionResponseDto> resolve(
      @Valid @RequestBody LocationResolutionRequestDto request) {
    return locationResolutionService.resolve(request);
  }
}
