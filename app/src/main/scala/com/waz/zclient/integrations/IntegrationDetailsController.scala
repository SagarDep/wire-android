/**
 * Wire
 * Copyright (C) 2018 Wire Swiss GmbH
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package com.waz.zclient.integrations

import android.content.Context
import com.waz.model.{IntegrationId, ProviderId}
import com.waz.utils.events.{EventStream, Signal, SourceSignal, SourceStream}
import com.waz.zclient.{Injectable, Injector}
import com.waz.zclient.common.controllers.IntegrationsController

class IntegrationDetailsController(implicit injector: Injector, context: Context) extends Injectable {

  private lazy val integrationsController = inject[IntegrationsController]

  val currentIntegrationId = Signal[(ProviderId, IntegrationId)]()
  val currentIntegration = currentIntegrationId.flatMap {
    case (pId, iId) => Signal.future(integrationsController.getIntegration(pId, iId))
  }

  val onAddServiceClick: SourceStream[Unit] = EventStream()
  val searchFilter: SourceSignal[String] = Signal("")
  val onClose: SourceStream[Unit] = EventStream()
}