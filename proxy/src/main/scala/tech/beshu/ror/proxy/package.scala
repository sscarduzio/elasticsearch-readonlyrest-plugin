/*
 *     Beshu Limited all rights reserved
 */
package tech.beshu.ror

import org.elasticsearch.client.{RestClient, RestClientBuilder, RestHighLevelClient}

package object proxy {

  new RestHighLevelClient(
    RestClient.builder()
  )
  val launchingBanner: String =
    """
      |
      |  _____                _  ____        _       _____  ______  _____ _______
      | |  __ \              | |/ __ \      | |     |  __ \|  ____|/ ____|__   __|
      | | |__) |___  __ _  __| | |  | |_ __ | |_   _| |__) | |__  | (___    | |     _ __  _ __ _____  ___   _
      | |  _  // _ \/ _` |/ _` | |  | | '_ \| | | | |  _  /|  __|  \___ \   | |    | '_ \| '__/ _ \ \/ / | | |
      | | | \ \  __/ (_| | (_| | |__| | | | | | |_| | | \ \| |____ ____) |  | |    | |_) | | | (_) >  <| |_| |
      | |_|  \_\___|\__,_|\__,_|\____/|_| |_|_|\__, |_|  \_\______|_____/   |_|    | .__/|_|  \___/_/\_\\__, |
      |                                         __/ |                              | |                   __/ |
      |                                        |___/                               |_|                  |___/
    """.stripMargin
}
