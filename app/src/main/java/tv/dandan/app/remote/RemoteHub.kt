package tv.dandan.app.remote

import android.content.Context
import tv.dandan.app.data.AppRepository

/** 手机输入与遥控共享一个应用级服务，页面和播放器都不重复绑定端口。 */
object RemoteHub {
    private var instance: RemoteControlServer? = null
    @Synchronized fun server(context: Context, repository: AppRepository): RemoteControlServer =
        instance ?: RemoteControlServer(context.applicationContext, repository).also { instance = it }
}
