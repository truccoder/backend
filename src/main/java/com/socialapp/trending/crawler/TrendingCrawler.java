package com.socialapp.trending.crawler;

import java.util.List;

import com.socialapp.trending.entity.enums.TrendingSource;

public interface TrendingCrawler {
  TrendingSource getSource();

  List<CrawledItem> crawl();
}
