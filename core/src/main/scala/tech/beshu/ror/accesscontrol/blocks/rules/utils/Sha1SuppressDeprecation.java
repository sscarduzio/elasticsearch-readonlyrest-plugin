package tech.beshu.ror.accesscontrol.blocks.rules.utils;

import com.google.common.hash.HashFunction;
import com.google.common.hash.Hashing;

public class Sha1SuppressDeprecation {
    @SuppressWarnings("deprecation")
    public static final HashFunction sha1 = Hashing.sha1();
}
