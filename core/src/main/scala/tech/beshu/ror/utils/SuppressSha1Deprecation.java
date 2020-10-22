package tech.beshu.ror.utils;

import com.google.common.hash.HashFunction;
import com.google.common.hash.Hashing;

public class SuppressSha1Deprecation {

    public static HashFunction sha1() {
        return Hashing.sha1();
    }
}
