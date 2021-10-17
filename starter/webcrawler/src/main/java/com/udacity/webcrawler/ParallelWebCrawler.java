package com.udacity.webcrawler;

import com.udacity.webcrawler.json.CrawlResult;
import com.udacity.webcrawler.parser.PageParser;
import com.udacity.webcrawler.parser.PageParserFactory;
import javax.inject.Inject;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.RecursiveAction;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Pattern;

final class ParallelWebCrawler implements WebCrawler {
  private final Clock clock;
  private final PageParserFactory parserFactory;
  private final Duration timeout;
  private final int popularWordCount;
  private final int maxDepth;
  private final List<Pattern> ignoredUrls;
  private final ForkJoinPool pool;

  @Inject
  ParallelWebCrawler(
          Clock clock,
          PageParserFactory parserFactory,
          @Timeout Duration timeout,
          @PopularWordCount int popularWordCount,
          @MaxDepth int maxDepth,
          @IgnoredUrls List<Pattern> ignoredUrls,
          @TargetParallelism int threadCount) {
    this.clock = clock;
    this.parserFactory = parserFactory;
    this.timeout = timeout;
    this.popularWordCount = popularWordCount;
    this.maxDepth = maxDepth;
    this.ignoredUrls = ignoredUrls;
    this.pool = new ForkJoinPool(Math.min(threadCount, getMaxParallelism()));
  }

  @Override
  public CrawlResult crawl(List<String> startingUrls) {
    Instant deadline = clock.instant().plus(timeout);
    Map<String, Integer> counts = Collections.synchronizedMap(new HashMap<>());
    Set<String> visitedUrls = Collections.synchronizedSet(new HashSet<>());
    for (String url : startingUrls) {
      pool.invoke(new WebCrawlAction(counts, visitedUrls, url, deadline, maxDepth));
    }

    if (counts.isEmpty()) {
      return new CrawlResult.Builder()
              .setWordCounts(counts)
              .setUrlsVisited(visitedUrls.size())
              .build();
    }

    return new CrawlResult.Builder()
            .setWordCounts(WordCounts.sort(counts, popularWordCount))
            .setUrlsVisited(visitedUrls.size())
            .build();
  }

  @Override
  public int getMaxParallelism() {
    return Runtime.getRuntime().availableProcessors();
  }

  public class WebCrawlAction extends RecursiveAction {
    private final Map<String, Integer> counts;
    private final Set<String> visitedUrls;
    private final String url;
    private final Instant deadline;
    private final int maxDepth;
    private final ReentrantLock lock = new ReentrantLock();

    public WebCrawlAction(Map<String, Integer> counts, Set<String> visitedUrls, String url, Instant deadline, int maxDepth) {
      this.counts = counts;
      this.visitedUrls = visitedUrls;
      this.url = url;
      this.deadline = deadline;
      this.maxDepth = maxDepth;
    }

    @Override
    protected void compute() {

      if (maxDepth == 0 || clock.instant().isAfter(deadline)) {
        return;
      }
      for (Pattern pattern : ignoredUrls) {
        if (pattern.matcher(url).matches()) {
          return;
        }
      }
      try {
        lock.lock();
        if (!visitedUrls.add(url)) {
          return;
        }
        visitedUrls.add(url);
        // process URL and return
      } finally {
        lock.unlock();
      }

      PageParser.Result result = parserFactory.get(url).parse();
      for (Map.Entry<String, Integer> e : result.getWordCounts().entrySet()) {
        if (counts.containsKey(e.getKey())) {
          counts.put(e.getKey(), e.getValue() + counts.get(e.getKey()));
        } else {
          counts.put(e.getKey(), e.getValue());
        }
      }
      List<WebCrawlAction> webCrawlActionList = new ArrayList<>();
      for (String link : result.getLinks()) {
        webCrawlActionList.add(new WebCrawlAction(counts, visitedUrls, link, deadline, maxDepth - 1));
      }
      invokeAll(webCrawlActionList);
    }
  }
}
