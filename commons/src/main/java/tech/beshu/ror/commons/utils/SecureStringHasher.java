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
package tech.beshu.ror.commons.utils;


import cz.seznam.euphoria.shaded.guava.com.google.common.hash.HashCode;
import cz.seznam.euphoria.shaded.guava.com.google.common.hash.HashFunction;
import cz.seznam.euphoria.shaded.guava.com.google.common.hash.Hashing;
import cz.seznam.euphoria.shaded.guava.com.google.common.primitives.Bytes;

import java.security.SecureRandom;

public class SecureStringHasher implements Hasher {

  private static byte[] salt;
  private final HashFunction hf;
  private final String algo;

  public SecureStringHasher(String algo) {

    int saltSize;
    switch (algo.toLowerCase()) {
      case "sha256":
        hf = Hashing.sha256();
        saltSize = 256;
        break;
      case "sha348":
        hf = Hashing.sha384();
        saltSize = 384;
        break;
      case "sha512":
        hf = Hashing.sha512();
        saltSize = 512;
        break;
      default:
        hf = Hashing.sha256();
        saltSize = 256;
    }
    this.salt = SecureRandom.getSeed(saltSize);
    this.algo = algo;
  }


  private static void rotate(byte[] arr, int order) {
    if (arr == null || order < 0) {
      throw new IllegalArgumentException("The array must be non-null and the order must be non-negative");
    }
    int offset = arr.length - order % arr.length;
    if (offset > 0) {
      byte[] copy = arr.clone();
      for (int i = 0; i < arr.length; ++i) {
        int j = (i + offset) % arr.length;
        arr[i] = copy[j];
      }
    }
  }

  public String getAlgo() {
    return algo;
  }

  public String hash(String originalKey) {
    int order = Hashing.consistentHash(HashCode.fromBytes(originalKey.getBytes()), salt.length);
    byte[] thisSalt = salt.clone();
    rotate(thisSalt, Math.abs(order));
    return hf.hashBytes(Bytes.concat(originalKey.getBytes(), thisSalt)).toString();
  }

}
