package com.flowpay.app
import android.Manifest;import android.app.*;import android.content.*;import android.content.pm.PackageManager
import androidx.core.app.*;import androidx.work.*;import kotlinx.coroutines.coroutineScope;import java.util.concurrent.TimeUnit
class PriceWorker(c:Context,p:WorkerParameters):CoroutineWorker(c,p){
 override suspend fun doWork():Result=coroutineScope{val s=Store(applicationContext);val old=s.wishes();var ok=0
  val fresh=old.map{x->runCatching{product(x.url)}.getOrNull()?.let{n->ok++;if(n.price>0&&x.price>0&&n.price<x.price)notice(x.name,x.price,n.price);x.copy(name=n.name.ifBlank{x.name},image=n.image.ifBlank{x.image},price=n.price.takeIf{it>0}?:x.price,history=(x.history+n.price).filter{it>0}.takeLast(90))}?:x}
  s.save(fresh);if(old.isNotEmpty()&&ok==0)Result.retry()else Result.success()}
 private fun notice(n:String,b:Double,p:Double){val m=applicationContext.getSystemService(NotificationManager::class.java);m.createNotificationChannel(NotificationChannel("drops","Зниження цін",NotificationManager.IMPORTANCE_DEFAULT))
  if(android.os.Build.VERSION.SDK_INT<33||applicationContext.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)==PackageManager.PERMISSION_GRANTED)NotificationManagerCompat.from(applicationContext).notify(n.hashCode(),NotificationCompat.Builder(applicationContext,"drops").setSmallIcon(android.R.drawable.star_big_on).setContentTitle("Ціна впала: $n").setContentText("${b.toInt()} ₴ → ${p.toInt()} ₴").build())}
 companion object{fun schedule(c:Context){val r=PeriodicWorkRequestBuilder<PriceWorker>(12,TimeUnit.HOURS).setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build();WorkManager.getInstance(c).enqueueUniquePeriodicWork("prices",ExistingPeriodicWorkPolicy.UPDATE,r)}}
}
