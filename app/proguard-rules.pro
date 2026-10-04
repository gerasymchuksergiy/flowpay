# FlowPay keeps no reflection of its own, so the defaults plus the consumer rules
# shipped by Compose, Coil and WorkManager cover almost everything. The two rules
# below guard the places Android instantiates a class by name.

# WorkManager builds its workers from the class names recorded in its database.
-keep class com.flowpay.app.PriceWorker { *; }
-keep class com.flowpay.app.ReminderWorker { *; }

# The launcher activity is named in the manifest.
-keep class com.flowpay.app.MainActivity { *; }

# Data holders are serialised by hand through org.json, but keeping their names
# makes a stack trace from a release build readable.
-keepnames class com.flowpay.app.Wish
-keepnames class com.flowpay.app.Pay
-keepnames class com.flowpay.app.Order

# The widget's tick and its «Скасувати» are Glance callbacks, built from their
# class names by reflection when tapped. Glance keeps the classes; the no-argument
# constructor it calls is kept here, so R8's full mode cannot drop it.
-keep class com.flowpay.app.WidgetMarkPaid { <init>(); }
-keep class com.flowpay.app.WidgetUndoMark { <init>(); }
