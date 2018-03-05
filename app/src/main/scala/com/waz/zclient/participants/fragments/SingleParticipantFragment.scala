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
import android.support.v4.app.Fragment
import android.support.v4.view.ViewPager
import android.view.animation.{AlphaAnimation, Animation}
import android.view.{LayoutInflater, View, ViewGroup}
import android.widget.{ImageView, TextView}
import com.waz.ZLog.ImplicitTag._
import com.waz.threading.Threading
import com.waz.utils._
import com.waz.zclient.common.controllers.{BrowserController, ThemeController, UserAccountsController}
import com.waz.zclient.conversation.ConversationController
import com.waz.zclient.pages.main.conversation.controller.IConversationScreenController
import com.waz.zclient.participants.fragments.SingleParticipantFragment.{ArgFirstPage, DevicePage}
import com.waz.zclient.participants.{ParticipantOtrDeviceAdapter, ParticipantsController, TabbedParticipantPagerAdapter}
import com.waz.zclient.ui.views.tab.TabIndicatorLayout
import com.waz.zclient.utils.ContextUtils._
import com.waz.zclient.utils.{RichView, StringUtils, ViewUtils}
import com.waz.zclient.views.menus.FooterMenuCallback
import com.waz.zclient.{FragmentHelper, R}

class SingleParticipantFragment extends FragmentHelper {
  import Threading.Implicits.Ui
  implicit def ctx: Context = getActivity

  private lazy val participantOtrDeviceAdapter = new ParticipantOtrDeviceAdapter

  private var viewPager = Option.empty[ViewPager]

  private lazy val convController         = inject[ConversationController]
  private lazy val participantsController = inject[ParticipantsController]
  private lazy val themeController        = inject[ThemeController]
  private lazy val screenController       = inject[IConversationScreenController]
  private lazy val userAccountsController = inject[UserAccountsController]
  private lazy val browserController      = inject[BrowserController]

  private lazy val otherUser = for {
    Some(userId) <- participantsController.otherParticipant
    user         <- participantsController.userSignal(userId)
  } yield user

  private lazy val userNameView = returning(view[TextView](R.id.user_name)) { vh =>
    participantsController.otherParticipant.map(_.isDefined).onUi { visible =>
      vh.foreach(_.setVisible(visible))
    }

    otherUser.map(_.getDisplayName).onUi { name =>
      vh.foreach(_.setText(name))
    }
  }

  private lazy val userHandle = returning(view[TextView](R.id.user_handle)) { vh =>
    val handle = otherUser.map(_.handle.map(_.string))
    handle.onUi { h => vh.foreach(_.setVisible(h.isDefined)) }
    handle.map {
      case Some(h) => StringUtils.formatHandle(h)
      case _       => ""
    }.onUi { str => vh.foreach(_.setText(str)) }
  }

  private lazy val userShield = returning(view[ImageView](R.id.single_user_verified_shield)){ vh =>
    otherUser.map(_.isVerified).onUi { isVerified => vh.foreach(_.setVisible(isVerified)) }
  }

  private lazy val callback = new FooterMenuCallback {

    override def onLeftActionClicked(): Unit =
      participantsController.isGroup.head.foreach {
        case false if userAccountsController.hasCreateConversationPermission =>
          screenController.addPeopleToConversation()
        case _ =>
          screenController.hideParticipants(true, false)
          participantsController.otherParticipant.head.foreach {
            case Some(userId) => userAccountsController.getOrCreateAndOpenConvFor(userId)
            case _ =>
          }
      }

    override def onRightActionClicked(): Unit = (for {
      isGroup <- participantsController.isGroup.head
      convId  <- convController.currentConvId.head
    } yield (isGroup, convId)).foreach {
      case (false, convId) => screenController.showConversationMenu(false, convId)
      case (true, convId) if userAccountsController.hasRemoveConversationMemberPermission(convId) =>
        participantsController.otherParticipant.head.foreach {
          case Some(userId) => participantsController.showRemoveConfirmation(userId)
          case _            =>
        }
      case _ =>
    }
  }

  // This is a workaround for the bug where child fragments disappear when
  // the parent is removed (as all children are first removed from the parent)
  // See https://code.google.com/p/android/issues/detail?id=55228
  // Apply the workaround only if this is a child fragment, and the parent is being removed.
  override def onCreateAnimation(transit: Int, enter: Boolean, nextAnim: Int): Animation =
    Option(getParentFragment) match {
      case Some(parent: Fragment) if enter && parent.isRemoving => returning(new AlphaAnimation(1, 1)){
        _.setDuration(ViewUtils.getNextAnimationDuration(parent))
      }
      case _ => super.onCreateAnimation(transit, enter, nextAnim)
    }

  override def onCreateView(inflater: LayoutInflater, viewGroup: ViewGroup, savedInstanceState: Bundle): View =
    returning(inflater.inflate(R.layout.fragment_participants_single_tabbed, viewGroup, false)) { v =>
      viewPager = Option(returning(findById[ViewPager](v, R.id.vp_single_participant_viewpager)) { pager =>
        pager.setAdapter(new TabbedParticipantPagerAdapter(participantOtrDeviceAdapter, callback))

        val tabIndicatorLayout = findById[TabIndicatorLayout](v, R.id.til_single_participant_tabs)
        tabIndicatorLayout.setPrimaryColor(getColorWithTheme(
          if (themeController.isDarkTheme) R.color.text__secondary_dark else R.color.text__secondary_light,
          getContext
        ))

        tabIndicatorLayout.setViewPager(pager)
      })

    }

  override def onViewCreated(v: View, @Nullable savedInstanceState: Bundle): Unit = {
    super.onViewCreated(v, savedInstanceState)

    if (Option(savedInstanceState).isEmpty) viewPager.foreach { pager =>
      if (screenController.shouldShowDevicesTab) {
        pager.setCurrentItem(DevicePage)
        screenController.setShowDevicesTab(null)
      } else
        pager.setCurrentItem(getArguments.getInt(ArgFirstPage))
    }

    participantOtrDeviceAdapter.onClientClick.onUi { client =>
      participantsController.otherParticipant.head.foreach {
        case Some(userId) =>
          Option(getParentFragment).foreach {
            case f: ParticipantFragment => f.showOtrClient(userId, client.id)
            case _ =>
          }
        case _ =>
      }
    }

    participantOtrDeviceAdapter.onHeaderClick {
      _ => browserController.openUrl(getString(R.string.url_otr_learn_why))
    }

    participantsController.showParticipantsRequest.onUi { case (_, showDeviceTabIfSingle) =>
      viewPager.foreach { pager =>
        if (screenController.shouldShowDevicesTab) {
          pager.setCurrentItem(DevicePage)
          screenController.setShowDevicesTab(null)
        } else participantsController.isGroup.head.foreach { isGroup =>
          if (!isGroup && showDeviceTabIfSingle) pager.setCurrentItem(DevicePage)
        }
      }
    }

    userNameView
    userHandle
    userShield
  }

  override def onDestroyView(): Unit = {
    viewPager = None
    super.onDestroyView()
  }

  def onBackPressed(): Boolean =
  if (participantsController.isGroupOrBot.currentValue.getOrElse(false)){
    screenController.hideUser()
    participantsController.unselectParticipant()
    true
  } else false

}

object SingleParticipantFragment {
  val Tag: String = classOf[SingleParticipantFragment].getName
  private val ArgFirstPage: String = "ArgFirstPage"
  val UserPage: Int = 0
  val DevicePage: Int = 1

  def newInstance(firstPage: Int): SingleParticipantFragment =
    returning(new SingleParticipantFragment){
      _.setArguments(returning(new Bundle){
        _.putInt(ArgFirstPage, firstPage)
      })
    }
}
