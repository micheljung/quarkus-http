/*
 * JBoss, Home of Professional Open Source.
 * Copyright 2014 Red Hat, Inc., and individual contributors
 * as indicated by the @author tags.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package io.undertow.util;

import java.util.Collections;

import org.junit.Assert;
import org.junit.Test;
import org.junit.experimental.categories.Category;

import io.undertow.security.impl.DigestAuthorizationToken;
import io.undertow.testutils.category.UnitTest;

/**
 * @author Stuart Douglas
 */
@Category(UnitTest.class)
public class HeaderTokenParserTestCase {

    @Test
    public void testHeaderTokenParser() {
        HeaderTokenParser<DigestAuthorizationToken> h = new HeaderTokenParser(Collections.<String, DigestAuthorizationToken>singletonMap("username", DigestAuthorizationToken.USERNAME));
        Assert.assertEquals("a\"b", h.parseHeader("username=\"a\\\"b\"").get(DigestAuthorizationToken.USERNAME));
    }
}
