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
package tech.beshu.ror.es.services

import tech.beshu.ror.es.DataStreamService.DataStreamSettings.{ComponentMappings, LifecyclePolicy}
import tech.beshu.ror.utils.DurationOps.RefinedDurationOps
import ujson.{Obj, Value}

private[services] object DataStreamSettingsOps {

  extension (mappings: ComponentMappings) {
    def mappingsJson: Value = {
      ujson.Obj("properties" -> ujson.Obj(
        mappings.timestampField -> ujson.Obj(
          "type" -> "date",
          "format" -> "date_optional_time||epoch_millis"
        )
      ))
    }
  }

  extension (policy: LifecyclePolicy) {

    def toJson: Value = {
      ujson.Obj(
        "phases" -> ujson.Obj.from(
          List("hot" -> toJson(policy.hotPhase)) ++
            policy.warmPhase.map(warmPhase => "warm" -> toJson(warmPhase)).toList ++
            policy.coldPhase.map(coldPhase => "cold" -> toJson(coldPhase)).toList
        )
      )
    }

    private def toJson(hotPhase: LifecyclePolicy.HotPhase): Obj = {
      ujson.Obj(
        "actions" -> ujson.Obj(
          "rollover" -> toJson(hotPhase.rollover)
        )
      )
    }

    private def toJson(warmPhase: LifecyclePolicy.WarmPhase): Obj = {
      ujson.Obj(
        "min_age" -> warmPhase.minAge.value.inShortFormat,
        "actions" -> ujson.Obj.from(
          warmPhase.shrink.map(shrink => "shrink" -> toJson(shrink)).toList ++
            warmPhase.forceMerge.map(forceMerge => "forcemerge" -> toJson(forceMerge)).toList
        )
      )
    }

    private def toJson(coldPhase: LifecyclePolicy.ColdPhase): Obj = {
      ujson.Obj(
        "min_age" -> coldPhase.minAge.value.inShortFormat,
        "actions" -> ujson.Obj.from(
          Option.when(coldPhase.freeze)("freeze" -> ujson.Obj()).toList
        )
      )
    }

    private def toJson(rollover: LifecyclePolicy.Rollover): Value = {
      ujson.Obj.from(
        List[(String, Value)]("max_age" -> rollover.maxAge.value.inShortFormat) ++
          rollover.maxPrimaryShardSizeInGb.map[(String, Value)](value => "max_primary_shard_size" -> s"${value.value}gb").toList
      )
    }

    private def toJson(shrink: LifecyclePolicy.Shrink): Value = {
      ujson.Obj(
        "number_of_shards" -> shrink.numberOfShards.value
      )
    }

    private def toJson(forceMerge: LifecyclePolicy.ForceMerge): Value = {
      ujson.Obj(
        "max_num_segments" -> forceMerge.maxNumSegments.value
      )
    }
  }
}
