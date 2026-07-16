import assert from "node:assert/strict";
import { test } from "node:test";
import { InMemoryIpRateLimiter } from "./ipRateLimiter.js";

test("InMemoryIpRateLimiter: allows requests up to the configured per-window cap", () => {
  const limiter = new InMemoryIpRateLimiter();
  // config default is 20/window in this test env; the first 20 calls for a fresh IP must pass.
  for (let i = 0; i < 20; i++) {
    assert.equal(limiter.isLimited("1.2.3.4"), false, `request ${i + 1} should not be limited`);
  }
});

test("InMemoryIpRateLimiter: the request that exceeds the cap is limited", () => {
  const limiter = new InMemoryIpRateLimiter();
  for (let i = 0; i < 20; i++) limiter.isLimited("1.2.3.4");

  assert.equal(limiter.isLimited("1.2.3.4"), true);
});

test("InMemoryIpRateLimiter: different IPs are tracked independently", () => {
  const limiter = new InMemoryIpRateLimiter();
  for (let i = 0; i < 20; i++) limiter.isLimited("1.2.3.4");

  assert.equal(limiter.isLimited("1.2.3.4"), true, "1.2.3.4 should now be limited");
  assert.equal(limiter.isLimited("5.6.7.8"), false, "a different IP should be unaffected");
});
