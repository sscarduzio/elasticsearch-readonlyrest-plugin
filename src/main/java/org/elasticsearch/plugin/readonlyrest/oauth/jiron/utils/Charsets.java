package org.elasticsearch.plugin.readonlyrest.oauth.jiron.utils;

/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import java.nio.charset.Charset;

public class Charsets {

    //
    // This class should only contain Charset instances for required encodings. This guarantees that it will load
    // correctly and without delay on all Java platforms.
    //

    public static Charset toCharset(Charset charset) {
        return charset == null ? Charset.defaultCharset() : charset;
    }

    public static Charset toCharset(String charset) {
        return charset == null ? Charset.defaultCharset() : Charset.forName(charset);
    }

    public static final Charset ISO_8859_1 = Charset.forName(CharEncoding.ISO_8859_1);

    public static final Charset US_ASCII = Charset.forName(CharEncoding.US_ASCII);

    public static final Charset UTF_16 = Charset.forName(CharEncoding.UTF_16);

    public static final Charset UTF_16BE = Charset.forName(CharEncoding.UTF_16BE);

    public static final Charset UTF_16LE = Charset.forName(CharEncoding.UTF_16LE);

    public static final Charset UTF_8 = Charset.forName(CharEncoding.UTF_8);
}
