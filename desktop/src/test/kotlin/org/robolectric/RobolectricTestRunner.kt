package org.robolectric

import org.junit.runners.BlockJUnit4ClassRunner

/**
 * Test-only stand-in so mirrored app tests that use Robolectric purely as a runner (they need
 * Android's org.json, which desktop gets from the real org.json artifact) run as plain JUnit.
 * Tests that need Robolectric's Android runtime are not mirrored (see desktop/build.gradle.kts).
 */
class RobolectricTestRunner(klass: Class<*>) : BlockJUnit4ClassRunner(klass)
