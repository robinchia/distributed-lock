/*
 * MIT License
 *
 * Copyright (c) 2018 Alen Turkovic
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package com.github.alturkovic.lock.jdbc.impl;

import com.github.alturkovic.lock.Lock;
import com.github.alturkovic.lock.jdbc.service.SimpleJdbcLockSingleKeyService;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import org.assertj.core.data.Offset;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.ColumnMapRowMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.SimpleJdbcInsert;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.context.junit4.SpringRunner;

import static org.assertj.core.api.Assertions.assertThat;

@DirtiesContext
@RunWith(SpringRunner.class)
@Sql(value = "/locks-table-create.sql", executionPhase = Sql.ExecutionPhase.BEFORE_TEST_METHOD)
@Sql(value = "/locks-table-drop.sql", executionPhase = Sql.ExecutionPhase.AFTER_TEST_METHOD)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
public class SimpleJdbcLockTest implements InitializingBean {

  @Autowired
  @SuppressWarnings("SpringJavaInjectionPointsAutowiringInspection") // false IntelliJ warning
  private JdbcTemplate jdbcTemplate;

  private Lock lock;

  @Override
  public void afterPropertiesSet() {
    // instead of writing a custom test configuration, we can just initialize it after autowiring mongoTemplate with a custom tokenSupplier
    lock = new SimpleJdbcLock(new SimpleJdbcLockSingleKeyService(jdbcTemplate), () -> "abc");
  }

  @Test
  public void shouldLock() {
    final long now = System.currentTimeMillis();
    final String token = lock.acquire(Collections.singletonList("1"), "locks", 1000);
    assertThat(token).isEqualTo("abc");

    final Map<String, Object> acquiredLockMap = jdbcTemplate.queryForObject("SELECT * FROM locks WHERE id = 1", new ColumnMapRowMapper());

    assertThat(acquiredLockMap).containsAllEntriesOf(values("1", "abc"));
    final Object expireAt = acquiredLockMap.get("expireAt");
    assertThat(((Date) expireAt).getTime()).isCloseTo(now + 1000, Offset.offset(100L));
  }

  @Test
  public void shouldNotLock() {
    new SimpleJdbcInsert(jdbcTemplate)
        .withTableName("locks")
        .usingGeneratedKeyColumns("id")
        .executeAndReturnKey(values("1", "def"));

    final String token = lock.acquire(Collections.singletonList("1"), "locks", 1000);
    assertThat(token).isNull();

    final Map<String, Object> acquiredLockMap = jdbcTemplate.queryForObject("SELECT * FROM locks WHERE id = 1", new ColumnMapRowMapper());
    assertThat(acquiredLockMap).containsAllEntriesOf(values("1", "def"));
  }

  @Test
  public void shouldRelease() {
    new SimpleJdbcInsert(jdbcTemplate)
        .withTableName("locks")
        .usingGeneratedKeyColumns("id")
        .executeAndReturnKey(values("1", "abc"));

    final boolean released = lock.release(Collections.singletonList("1"), "abc", "locks");
    assertThat(released).isTrue();
    assertThat(jdbcTemplate.queryForList("SELECT * FROM locks")).isNullOrEmpty();
  }

  @Test
  public void shouldNotRelease() {
    new SimpleJdbcInsert(jdbcTemplate)
        .withTableName("locks")
        .usingGeneratedKeyColumns("id")
        .executeAndReturnKey(values("1", "def"));

    lock.release(Collections.singletonList("1"), "abc", "locks");

    final Map<String, Object> acquiredLockMap = jdbcTemplate.queryForObject("SELECT * FROM locks WHERE id = 1", new ColumnMapRowMapper());
    assertThat(acquiredLockMap).containsAllEntriesOf(values("1", "def"));
  }

  private static Map<String, Object> values(final String key, final String token) {
    final Map<String, Object> values = new HashMap<>();
    values.put("key", key);
    values.put("token", token);
    return values;
  }

  @SpringBootApplication
  static class TestApplication {
  }
}