package com.seoul2033wiki

import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

/**
 * Firebase Cloud Messaging 수신 서비스.
 *
 * 운영자가 Firebase 콘솔 → Cloud Messaging → "새 캠페인"에서 제목/내용을 입력해
 * 전송하면, 설치된 모든 기기에 이 서비스가 메시지를 받아 알림으로 띄운다.
 * 콘솔에서 "앱" 대상으로 보내는 한 토큰 등록이나 토픽 구독 같은 추가 작업은 필요 없다.
 *
 * 주의: 앱이 백그라운드/종료 상태일 때 콘솔에서 보낸 일반 알림(제목+내용)은
 * 시스템이 onMessageReceived() 없이 자동으로 알림을 띄워준다. 이 클래스의
 * onMessageReceived()는 앱이 포그라운드일 때(또는 데이터 전용 메시지일 때)만
 * 호출되어 직접 알림을 그려야 하는 경우를 처리한다.
 */
class Seoul2033FirebaseMessagingService : FirebaseMessagingService() {

    companion object {
        private const val CHANNEL_ID = "manual_alert_channel"
    }

    override fun onMessageReceived(message: RemoteMessage) {
        super.onMessageReceived(message)

        val title = message.notification?.title ?: message.data["title"] ?: "서울2033 리더"
        val body = message.notification?.body ?: message.data["body"] ?: return

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "공지 알림", NotificationManager.IMPORTANCE_DEFAULT
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .build()

        try {
            NotificationManagerCompat.from(this).notify(System.currentTimeMillis().toInt(), notification)
        } catch (_: SecurityException) {
            // 알림 권한이 없으면 조용히 무시
        }
    }

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        // 지금 구조(콘솔에서 "앱 전체"로 보내는 캠페인)에서는 토큰을 따로 안 써도 됨.
        // 나중에 특정 사용자만 골라 보내고 싶어지면, 이 토큰을 서버에 등록해야 함.
    }
}
