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
package com.waz.zclient.participants.fragments

import android.content.Context
import android.os.Bundle
import android.support.annotation.Nullable
import android.support.v4.app.FragmentManager
import android.view.animation.Animation
import android.view.{LayoutInflater, View, ViewGroup}
import com.waz.ZLog.ImplicitTag._
import com.waz.ZLog._
import com.waz.api._
import com.waz.model._
import com.waz.model.otr.ClientId
import com.waz.threading.Threading
import com.waz.utils.events.{Signal, Subscription}
import com.waz.utils.returning
import com.waz.zclient.controllers.navigation.{INavigationController, Page}
import com.waz.zclient.controllers.singleimage.ISingleImageController
import com.waz.zclient.conversation.ConversationController
import com.waz.zclient.core.stores.connect.IConnectStore
import com.waz.zclient.core.stores.conversation.ConversationChangeRequester
import com.waz.zclient.core.stores.conversation.ConversationChangeRequester._
import com.waz.zclient.integrations.IntegrationDetailsFragment
import com.waz.zclient.pages.BaseFragment
import com.waz.zclient.pages.main.connect.{BlockedUserProfileFragment, PendingConnectRequestFragment, SendConnectRequestFragment}
import com.waz.zclient.pages.main.conversation.controller.{ConversationScreenControllerObserver, IConversationScreenController}
import com.waz.zclient.pages.main.pickuser.controller.IPickUserController
import com.waz.zclient.participants.{OptionsMenuFragment, ParticipantsController}
import com.waz.zclient.usersearch.SearchUIFragment
import com.waz.zclient.utils.ContextUtils._
import com.waz.zclient.utils.ViewUtils
import com.waz.zclient.views.DefaultPageTransitionAnimation
import com.waz.zclient.{FragmentHelper, OnBackPressedListener, R}

class ParticipantFragment extends BaseFragment[ParticipantFragment.Container] with FragmentHelper
  with ConversationScreenControllerObserver
  with OnBackPressedListener
  with ParticipantHeaderFragment.Container
  with SendConnectRequestFragment.Container
  with BlockedUserProfileFragment.Container
  with PendingConnectRequestFragment.Container {

  implicit def ctx: Context = getActivity
  import Threading.Implicits.Ui

  private lazy val bodyContainer             = view[View](R.id.fl__participant__container)
  private lazy val participantsContainerView = view[View](R.id.ll__participant__container)

  private lazy val convChange = convController.convChanged.filter { _.to.isDefined }

  private lazy val convController         = inject[ConversationController]
  private lazy val participantsController = inject[ParticipantsController]
  private lazy val screenController       = inject[IConversationScreenController]
  private lazy val pickUserController     = inject[IPickUserController]
  private lazy val singleImageController  = inject[ISingleImageController]
  private lazy val navigationController   = inject[INavigationController]

  private var subs = Set.empty[Subscription]

  private lazy val headerFragment = ParticipantHeaderFragment.newInstance

  val navigationIconVisible = Signal(true)

  def setNavigationIconVisible(isVisible: Boolean): Unit =
    navigationIconVisible ! isVisible

  override def onCreateAnimation(transit: Int, enter: Boolean, nextAnim: Int): Animation =
    if (nextAnim == 0 || Option(getContainer).isEmpty || getControllerFactory.isTornDown)
      super.onCreateAnimation(transit, enter, nextAnim)
    else new DefaultPageTransitionAnimation(
      0,
      getDimenPx(R.dimen.open_new_conversation__thread_list__max_top_distance),
      enter,
      getInt(R.integer.framework_animation_duration_medium),
      if (enter) getInt(R.integer.framework_animation_duration_medium) else 0,
      1f
    )

  override def onCreateView(inflater: LayoutInflater, container: ViewGroup, savedInstanceState: Bundle): View =
    returning(inflater.inflate(R.layout.fragment_participant, container, false)) { _ =>
      val fragmentManager = getChildFragmentManager
      Option(fragmentManager.findFragmentById(R.id.fl__participant__overlay)).foreach {
        fragmentManager.beginTransaction.remove(_).commit
      }
    }

  override def onViewCreated(view: View, @Nullable savedInstanceState: Bundle): Unit = {
    if (Option(savedInstanceState).isEmpty)
      participantsController.isGroupOrBot.head.foreach { groupOrBot =>
        getChildFragmentManager.beginTransaction
        .setCustomAnimations(
          R.anim.open_new_conversation__thread_list_in,
          R.anim.open_new_conversation__thread_list_out,
          R.anim.open_new_conversation__thread_list_in,
          R.anim.open_new_conversation__thread_list_out
        )
        .replace(
          R.id.fl__participant__header__container,
          headerFragment,
          ParticipantHeaderFragment.TAG
        )
        .replace(
          R.id.fl__participant__settings_box,
          OptionsMenuFragment.newInstance(false),
          OptionsMenuFragment.Tag
        )
        .replace(
          R.id.fl__participant__container,
          if (groupOrBot)
            GroupParticipantsFragment.newInstance()
          else
            SingleParticipantFragment.newInstance(getArguments.getInt(ParticipantFragment.ARG__FIRST__PAGE)),
          if (groupOrBot)
            GroupParticipantsFragment.Tag
          else
            SingleParticipantFragment.Tag
        )
        .commit
      }

    bodyContainer
    participantsContainerView

    subs += convChange.map(_.requester).onUi {
      case START_CONVERSATION | START_CONVERSATION_FOR_VIDEO_CALL | START_CONVERSATION_FOR_CALL | START_CONVERSATION_FOR_CAMERA =>
        getChildFragmentManager.popBackStackImmediate(SearchUIFragment.TAG, FragmentManager.POP_BACK_STACK_INCLUSIVE)
        pickUserController.hidePickUserWithoutAnimations(IPickUserController.Destination.PARTICIPANTS)
      case _ =>
    }

    subs += participantsController.conv.map(_.isActive).onUi { screenController.setMemberOfConversation }

    subs += participantsController.isGroupOrBot.onUi { isGroupOrBot =>
      screenController.setSingleConversation(!isGroupOrBot)
    }

  }

  override def onStart(): Unit = {
    super.onStart()
    screenController.addConversationControllerObservers(this)
  }

  override def onStop(): Unit = {
    screenController.removeConversationControllerObservers(this)
    super.onStop()
  }

  override def onDestroyView(): Unit = {
    singleImageController.clearReferences()
    subs.foreach(_.destroy())
    subs = Set.empty[Subscription]

    super.onDestroyView()
  }

  override def onBackPressed: Boolean = withBackstackHead {
    case Some(f: SingleParticipantFragment) if f.onBackPressed() =>
      verbose(s"onBackPressed with SingleParticipantFragment")
      true
    case Some(f: GroupParticipantsFragment) if f.onBackPressed() =>
      verbose(s"onBackPressed with GroupParticipantsFragment")
      true
    case Some(f: SearchUIFragment) if f.onBackPressed() =>
      verbose(s"onBackPressed with PickUserFragment")
      true
    case Some(f: SingleOtrClientFragment) =>
      verbose(s"onBackPressed with SingleOtrClientFragment")
      screenController.hideOtrClient()
      true
    case Some(f: OptionsMenuFragment) if f.close() =>
      verbose(s"onBackPressed with OptionsMenuFragment")
      true
    case Some(f: IntegrationDetailsFragment) if f.onBackPressed() =>
      verbose(s"onBackPressed with IntegrationDetailsFragment")
      true
    case Some(f: GuestOptionsFragment) if f.onBackPressed() =>
      verbose(s"onBackPressed with GuestOptionsFragment")
      true
    case _ if pickUserController.isShowingPickUser(IPickUserController.Destination.PARTICIPANTS) =>
      verbose(s"onBackPressed with isShowingPickUser")
      pickUserController.hidePickUser(IPickUserController.Destination.PARTICIPANTS)
      true
    case _ if screenController.isShowingUser =>
      verbose(s"onBackPressed with screenController.isShowingUser")
      screenController.hideUser()
      participantsController.unselectParticipant()
      true
    case _ if screenController.isShowingParticipant =>
      verbose(s"onBackPressed with isShowingParticipant")
      screenController.hideParticipants(true, false)
      true
    case _ =>
      verbose(s"onBackPressed not handled here")
      false
  }

  override def onShowEditConversationName(show: Boolean): Unit =
    bodyContainer.foreach { view =>
      if (show) ViewUtils.fadeOutView(view)
      else ViewUtils.fadeInView(view)
    }

  override def onAddPeopleToConversation(): Unit =
    pickUserController.showPickUser(IPickUserController.Destination.PARTICIPANTS)

  override def onShowConversationMenu(inConvList: Boolean, convId: ConvId): Unit =
    if (!inConvList) getChildFragmentManager.findFragmentByTag(OptionsMenuFragment.Tag) match {
      case fragment: OptionsMenuFragment => fragment.open(convId)
      case _ =>
    }

  def showOtrClient(userId: UserId, clientId: ClientId): Unit =
    getChildFragmentManager
      .beginTransaction
      .setCustomAnimations(
        R.anim.fragment_animation_second_page_slide_in_from_right,
        R.anim.fragment_animation_second_page_slide_out_to_left,
        R.anim.fragment_animation_second_page_slide_in_from_left,
        R.anim.fragment_animation_second_page_slide_out_to_right)
      .add(
        R.id.fl__participant__overlay,
        SingleOtrClientFragment.newInstance(userId, clientId),
        SingleOtrClientFragment.Tag
      )
      .addToBackStack(SingleOtrClientFragment.Tag)
      .commit

  def showCurrentOtrClient(): Unit =
    getChildFragmentManager
      .beginTransaction
      .setCustomAnimations(
        R.anim.slide_in_from_bottom_pick_user,
        R.anim.open_new_conversation__thread_list_out,
        R.anim.open_new_conversation__thread_list_in,
        R.anim.slide_out_to_bottom_pick_user)
      .add(
        R.id.fl__participant__overlay,
        SingleOtrClientFragment.newInstance,
        SingleOtrClientFragment.Tag
      )
      .addToBackStack(SingleOtrClientFragment.Tag)
      .commit

  // TODO: AN-5980
  def showIntegrationDetails(pId: ProviderId, iId: IntegrationId): Unit = {
    getChildFragmentManager
      .beginTransaction
      .setCustomAnimations(
        R.anim.fragment_animation_second_page_slide_in_from_right,
        R.anim.fragment_animation_second_page_slide_out_to_left,
        R.anim.fragment_animation_second_page_slide_in_from_left,
        R.anim.fragment_animation_second_page_slide_out_to_right
      )
      .replace(
        R.id.fl__participant__overlay,
        IntegrationDetailsFragment.newInstance(pId, iId, isTransparent = false),
        IntegrationDetailsFragment.Tag
      )
      .addToBackStack(IntegrationDetailsFragment.Tag)
      .commit
  }

  override def onHideUser(): Unit = if (screenController.isShowingUser) {
    getChildFragmentManager.popBackStack
    navigationController.setRightPage(if (screenController.isShowingParticipant) Page.PARTICIPANT else Page.MESSAGE_STREAM, ParticipantFragment.TAG)
  }

  override def showRemoveConfirmation(userId: UserId): Unit =
    participantsController.showRemoveConfirmation(userId)

  override def dismissUserProfile(): Unit = screenController.hideUser()

  override def dismissSingleUserProfile(): Unit = dismissUserProfile()

  override def onAcceptedConnectRequest(conversation: ConvId): Unit = {
    screenController.hideUser()
    verbose(s"onAcceptedConnectRequest $conversation")
    convController.selectConv(conversation, ConversationChangeRequester.START_CONVERSATION)
  }

  override def onUnblockedUser(restoredConversationWithUser: ConvId): Unit = {
    screenController.hideUser()
    verbose(s"onUnblockedUser $restoredConversationWithUser")
    convController.selectConv(restoredConversationWithUser, ConversationChangeRequester.START_CONVERSATION)
  }

  override def onConnectRequestWasSentToUser(): Unit = screenController.hideUser()

  override def onShowParticipants(anchorView: View, isSingleConversation: Boolean, isMemberOfConversation: Boolean, showDeviceTabIfSingle: Boolean): Unit = {}

  override def onHideParticipants(backOrButtonPressed: Boolean, hideByConversationChange: Boolean, isSingleConversation: Boolean): Unit = {}

  override def onHideOtrClient(): Unit = getChildFragmentManager.popBackStack

  override def onShowLikesList(message: Message): Unit = {}

  override def onShowIntegrationDetails(providerId: ProviderId, integrationId: IntegrationId): Unit = {}

  override def onConversationUpdated(conversation: ConvId): Unit = {}
}

object ParticipantFragment {
  val TAG: String = classOf[ParticipantFragment].getName
  private val ARG_USER_REQUESTER = "ARG_USER_REQUESTER"
  private val ARG__FIRST__PAGE = "ARG__FIRST__PAGE"

  def newInstance(userRequester: IConnectStore.UserRequester, firstPage: Int): ParticipantFragment =
    returning(new ParticipantFragment) {
      _.setArguments(returning(new Bundle) { args =>
        args.putSerializable(ARG_USER_REQUESTER, userRequester)
        args.putInt(ARG__FIRST__PAGE, firstPage)
      })
    }

  trait Container {}

}
