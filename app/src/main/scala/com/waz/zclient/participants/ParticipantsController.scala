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
package com.waz.zclient.participants

import android.content.Context
import android.view.View
import com.waz.ZLog.ImplicitTag._
import com.waz.ZLog.warn
import com.waz.model._
import com.waz.service.ZMessaging
import com.waz.threading.Threading
import com.waz.utils.events.{EventContext, EventStream, Signal}
import com.waz.zclient.common.controllers.{SoundController, ThemeController}
import com.waz.zclient.controllers.confirmation.{ConfirmationRequest, IConfirmationController, TwoButtonConfirmationCallback}
import com.waz.zclient.conversation.ConversationController
import com.waz.zclient.pages.main.conversation.controller.IConversationScreenController
import com.waz.zclient.utils.ContextUtils._
import com.waz.zclient.utils.{Callback, UiStorage, UserSignal}
import com.waz.zclient.{Injectable, Injector, R}

import scala.concurrent.Future

class ParticipantsController(implicit injector: Injector, context: Context, ec: EventContext) extends Injectable {

  import com.waz.threading.Threading.Implicits.Background

  private implicit lazy val uiStorage     = inject[UiStorage]
  private lazy val zms                    = inject[Signal[ZMessaging]]
  private lazy val convController         = inject[ConversationController]
  private lazy val confirmationController = inject[IConfirmationController]
  private lazy val screenController       = inject[IConversationScreenController]

  private lazy val selectedParticipant = Signal(Option.empty[UserId])
  val showParticipantsRequest = EventStream[(View, Boolean)]()

  lazy val otherParticipants = convController.currentConvMembers
  lazy val conv              = convController.currentConv
  lazy val isGroup           = convController.currentConvIsGroup

  lazy val otherParticipant = otherParticipants.flatMap {
    case others if others.size == 1 => Signal.const(others.headOption)
    case others                     => selectedParticipant.map(_.flatMap(id => others.find(_ == id)))
  }

  lazy val containsGuest = for {
    z     <- zms
    ids   <- otherParticipants
    isGroup <- convController.currentConvIsGroup
    users <- Signal.sequence(ids.map(z.users.userSignal).toSeq:_*)
  } yield isGroup && users.exists(_.isGuest(z.teamId))

  lazy val isWithBot = for {
    z       <- zms
    others  <- otherParticipants
    withBot <- Signal.sequence(others.map(id => z.users.userSignal(id).map(_.isWireBot)).toSeq: _*)
  } yield withBot.contains(true)

  lazy val isGroupOrBot = for {
    group      <- isGroup
    groupOrBot <- if (group) Signal.const(true) else isWithBot
  } yield groupOrBot

  // is the current user a guest in the current conversation
  lazy val isCurrentUserGuest: Signal[Boolean] = for {
    z           <- zms
    currentUser <- UserSignal(z.selfUserId)
    currentConv <- conv
  } yield currentConv.team.isDefined && currentConv.team != currentUser.teamId

  lazy val currentUserBelongsToConversationTeam: Signal[Boolean] = for {
    z           <- zms
    currentUser <- UserSignal(z.selfUserId)
    currentConv <- conv
  } yield currentConv.team.isDefined && currentConv.team == currentUser.teamId

  def selectParticipant(userId: UserId): Unit = selectedParticipant ! Some(userId)

  def unselectParticipant(): Unit = selectedParticipant ! None

  def getUser(userId: UserId): Future[Option[UserData]] = zms.head.flatMap(_.users.getUser(userId))

  def userSignal(userId: UserId): Signal[UserData] = zms.flatMap(_.users.userSignal(userId))

  def addMembers(userIds: Set[UserId]): Future[Unit] =
    convController.currentConvId.head.flatMap { convId => convController.addMembers(convId, userIds) }

  def blockUser(userId: UserId): Future[Option[UserData]] = zms.head.flatMap(_.connection.blockConnection(userId))

  def unblockUser(userId: UserId): Future[ConversationData] = zms.head.flatMap(_.connection.unblockConnection(userId))

  def withOtherParticipant(callback: Callback[UserData]): Unit =
    otherParticipant.head.flatMap {
      case Some(userId) => getUser(userId)
      case None => Future.successful(None)
    }.foreach {
      case Some(userData) => callback.callback(userData)
      case None => warn("Unable to get the other participant for the current conversation")
    }(Threading.Ui)

  def withCurrentConvGroupOrBot(callback: Callback[java.lang.Boolean]): Unit =
    isGroupOrBot.head.foreach { groupOrBot => callback.callback(groupOrBot) }(Threading.Ui)

  def showRemoveConfirmation(userId: UserId): Unit = getUser(userId).foreach {
    case Some(userData) =>
      val request = new ConfirmationRequest.Builder()
        .withHeader(getString(R.string.confirmation_menu__header))
        .withMessage(getString(R.string.confirmation_menu_text_with_name, userData.getDisplayName))
        .withPositiveButton(getString(R.string.confirmation_menu__confirm_remove))
        .withNegativeButton(getString(R.string.confirmation_menu__cancel))
        .withConfirmationCallback(new TwoButtonConfirmationCallback() {
          override def positiveButtonClicked(checkboxIsSelected: Boolean): Unit = {
            screenController.hideUser()
            convController.removeMember(userId)
          }

          override def negativeButtonClicked(): Unit = {}
          override def onHideAnimationEnd(confirmed: Boolean, canceled: Boolean, checkboxIsSelected: Boolean): Unit = {}
        })
        .withWireTheme(inject[ThemeController].getThemeDependentOptionsTheme)
        .build
      confirmationController.requestConfirmation(request, IConfirmationController.PARTICIPANTS)
      inject[SoundController].playAlert()
    case _ =>
  }(Threading.Ui)

}
