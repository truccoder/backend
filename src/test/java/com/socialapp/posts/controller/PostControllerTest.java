package com.socialapp.posts.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
public class PostControllerTest {

  @Autowired private MockMvc mockMvc;

  @Test
  public void testCreatePost_Success() throws Exception {
    String jsonPayload =
        """
        {
          "content": "This is my first post!",
          "visibility": "PUBLIC",
          "images": [
            "http://localhost:9000/socialapp-images/test-image.jpg"
          ],
          "location": {
            "google_place_id": "ChIJ06bviqYpdTERK7gywzsv8to",
            "location_type": "PLACE",
            "display_name": "Nhà thờ Đức Bà Sài Gòn",
            "latitude": 10.7797855,
            "longitude": 106.6990189,
            "city": "Thành phố Hồ Chí Minh",
            "country": "Việt Nam"
          }
        }
        """;

    mockMvc
        .perform(post("/v1/api/posts").contentType(MediaType.APPLICATION_JSON).content(jsonPayload))
        .andExpect(status().isNoContent());
  }
}
