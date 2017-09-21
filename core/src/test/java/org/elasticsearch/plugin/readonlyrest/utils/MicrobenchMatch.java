package org.elasticsearch.plugin.readonlyrest.utils;

import com.google.common.collect.Sets;
import org.apache.commons.lang.RandomStringUtils;

import java.util.Set;


public class MicrobenchMatch {

  public static void main(String[] args) {

    String haystack = RandomStringUtils.random(10) + "logstash-*" + RandomStringUtils.random(10);
    Set<String> needles = Sets.newHashSet("*logstash-*");
    int iterations = (int) Math.pow(10, 7);
    Long start;


    // OLD
    MatcherWithWildcards matcher = new MatcherWithWildcards(needles);
    start = System.currentTimeMillis();
    for (int i = 0; i < iterations; i++) {
      if (!matcher.match(haystack + i)) throw new RuntimeException("lol");
    }
    Long timeOld = System.currentTimeMillis() - start;
    System.out.println("old: " + timeOld+ " ms");


    // NEW
    MatcherWithWildcards minimatch = new MatcherWithWildcards(needles);
    start = System.currentTimeMillis();
    for (int i = 0; i < iterations; i++) {
      if (!minimatch.match(haystack + i)) throw new RuntimeException("lol");
    }
    Long timeNew = (System.currentTimeMillis() - start);
    System.out.println("new: " + (System.currentTimeMillis() - start) + " ms");

    System.out.println("Gain: " +  (((timeOld-timeNew)*1f) / timeOld)*100 + "%");

  }
}