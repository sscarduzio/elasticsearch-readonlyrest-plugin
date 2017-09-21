/*
 *    This file is part of ReadonlyREST.
 *
 *    ReadonlyREST is free software: you can redistribute it and/or modify
 *    it under the terms of the GNU General Public License as published by
 *    the Free Software Foundation, either version 3 of the License, or
 *    (at your option) any later version.
 *
 *    ReadonlyREST is distributed in the hope that it will be useful,
 *    but WITHOUT ANY WARRANTY; without even the implied warranty of
 *    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *    GNU General Public License for more details.
 *
 *    You should have received a copy of the GNU General Public License
 *    along with ReadonlyREST.  If not, see http://www.gnu.org/licenses/
 */

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