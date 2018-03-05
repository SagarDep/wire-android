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
package com.waz.zclient.common.views

import android.content.Context
import android.graphics.Color
import android.support.v4.content.ContextCompat
import android.support.v7.widget.AppCompatCheckBox
import android.util.AttributeSet
import android.view.View
import android.view.View.OnClickListener
import android.widget.{CompoundButton, ImageView, RelativeLayout}
import com.waz.model.{Availability, IntegrationData, TeamId, UserData}
import com.waz.utils.events.{EventStream, SourceStream}
import com.waz.utils.returning
import com.waz.zclient.common.views.SingleUserRowView._
import com.waz.zclient.paintcode.{ForwardNavigationIcon, GuestIcon}
import com.waz.zclient.ui.text.TypefaceTextView
import com.waz.zclient.utils.{ContextUtils, StringUtils}
import com.waz.zclient.views.AvailabilityView
import com.waz.zclient.{R, ViewHelper}

class SingleUserRowView(context: Context, attrs: AttributeSet, style: Int) extends RelativeLayout(context, attrs, style) with ViewHelper {
  def this(context: Context, attrs: AttributeSet) = this(context, attrs, 0)
  def this(context: Context) = this(context, null, 0)

  inflate(R.layout.single_user_row_view)
  setTheme(Light)

  private lazy val chathead = findById[ChatheadView](R.id.chathead)
  private lazy val nameView = findById[TypefaceTextView](R.id.name_text)
  private lazy val usernameView = findById[TypefaceTextView](R.id.username_text)
  private lazy val checkbox = findById[AppCompatCheckBox](R.id.checkbox)
  private lazy val verifiedShield = findById[ImageView](R.id.verified_shield)
  private lazy val guestIndicator = returning(findById[ImageView](R.id.guest_indicator))(_.setImageDrawable(GuestIcon(R.color.light_graphite)))
  private lazy val nextIndicator = returning(findById[ImageView](R.id.next_indicator))(_.setImageDrawable(ForwardNavigationIcon(R.color.light_graphite_40)))

  val onSelectionChanged: SourceStream[Boolean] = EventStream()

  checkbox.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener {
    override def onCheckedChanged(buttonView: CompoundButton, isChecked: Boolean): Unit =
      onSelectionChanged ! isChecked
  })

  this.setOnClickListener(new OnClickListener {
    override def onClick(v: View): Unit = setChecked(!checkbox.isChecked)
  })

  def setTitle(text: String): Unit = {
    nameView.setText(text)
  }

  def setSubtitle(text: Option[String]): Unit = text.fold(usernameView.setVisibility(View.GONE)) { t =>
    usernameView.setVisibility(View.VISIBLE)
    usernameView.setText(t)
  }

  def setChecked(checked: Boolean): Unit = checkbox.setChecked(checked)

  private def setVerified(verified: Boolean) = verifiedShield.setVisibility(if (verified) View.VISIBLE else View.GONE)

  def showArrow(show: Boolean): Unit = nextIndicator.setVisibility(if (show) View.VISIBLE else View.GONE)

  def setUserData(userData: UserData, teamId: Option[TeamId]): Unit = {
    chathead.setUserId(userData.id)
    setTitle(userData.getDisplayName)
    if (teamId.isDefined) setAvailability(userData.availability)
    setVerified(userData.isVerified)
    setSubtitle(userData.handle.map(h => StringUtils.formatHandle(h.string)))
    setIsGuest(userData.isGuest(teamId))
  }

  def setIntegration(integration: IntegrationData): Unit = {
    chathead.setIntegration(integration)
    setTitle(integration.name)
    setAvailability(Availability.None)
    setVerified(false)
    setSubtitle(Some(integration.summary))
  }

  def setIsGuest(guest: Boolean): Unit = guestIndicator.setVisibility(if (guest) View.VISIBLE else View.GONE)

  def showCheckbox(show: Boolean): Unit = checkbox.setVisibility(if (show) View.VISIBLE else View.GONE)

  //TODO: Proper colors and other stuff
  def setTheme(theme: Theme): Unit = {
    theme match {
      case Light =>
        returning(ContextCompat.getDrawable(getContext, R.drawable.checkbox_black)){ btn =>
          btn.setLevel(1)
          checkbox.setButtonDrawable(btn)
        }
        nameView.setTextColor(ContextUtils.getColor(R.color.wire__text_color_primary_light_selector))
        setBackgroundColor(Color.WHITE)
      case Dark =>
        returning(ContextCompat.getDrawable(getContext, R.drawable.checkbox)){ btn =>
          btn.setLevel(1)
          checkbox.setButtonDrawable(btn)
        }
        nameView.setTextColor(ContextUtils.getColor(R.color.wire__text_color_primary_dark_selector))
        setBackgroundColor(Color.BLACK)
      case Transparent =>
        returning(ContextCompat.getDrawable(getContext, R.drawable.checkbox)){ btn =>
          btn.setLevel(1)
          checkbox.setButtonDrawable(btn)
        }
        nameView.setTextColor(ContextUtils.getColor(R.color.wire__text_color_primary_dark_selector))
        setBackground(ContextUtils.getDrawable(R.drawable.selector__transparent_button))
    }
  }

  def setAvailability(availability: Availability): Unit =
    AvailabilityView.displayLeftOfText(nameView, availability, nameView.getCurrentTextColor, pushDown = true)
}

object SingleUserRowView {
  trait Theme
  object Light extends Theme
  object Dark extends Theme
  object Transparent extends Theme
}
