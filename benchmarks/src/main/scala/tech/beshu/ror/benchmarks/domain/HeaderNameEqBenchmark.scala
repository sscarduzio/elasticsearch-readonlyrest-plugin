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
package tech.beshu.ror.benchmarks.domain

import cats.syntax.eq.*
import eu.timepit.refined.types.string.NonEmptyString
import org.openjdk.jmh.annotations.*
import org.openjdk.jmh.infra.Blackhole
import tech.beshu.ror.accesscontrol.domain.{Credentials, Header, PlainTextSecret, User}
import tech.beshu.ror.benchmarks.support.BenchmarkSupport.{nes, realisticHeaders}
import tech.beshu.ror.syntax.*

import java.util.concurrent.TimeUnit

/**
 * Tier-2 KPI: the PRODUCTION header lookup — `Set[Header].find(_.name === target)` with the
 * case-insensitive `Header.Name` Eq, over the same realistic 20-header set the ACL sees.
 * Measures the real idiom (Set iteration + cats Eq syntax), not a hand-rolled array scan.
 */
@State(Scope.Thread)
@BenchmarkMode(Array(Mode.AverageTime))
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Fork(2)
class HeaderNameEqBenchmark {

  private implicit val eqName: cats.Eq[Header.Name] = Header.Name.eqName

  private var headers: Set[Header] = scala.compiletime.uninitialized
  private var target: Header.Name = scala.compiletime.uninitialized

  @Setup(Level.Trial)
  def setup(): Unit = {
    headers = realisticHeaders(Credentials(User.Id(nes("user1")), PlainTextSecret(nes("pass1"))))
    target = Header.Name(NonEmptyString.unsafeFrom("Authorization"))
  }

  @Benchmark
  def findHeader(bh: Blackhole): Unit =
    bh.consume(headers.find(_.name === target))
}
