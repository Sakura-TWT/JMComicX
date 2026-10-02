# Gson reads these persisted models by field name through reflection.
-keep class app.prismia.plus.AccountProfile { <fields>; }
-keep class app.prismia.plus.AccountCredentials { <fields>; }
-keep class app.prismia.plus.core.session.PersistentCookieStore$PersistedCookie { <fields>; }
-keep class app.prismia.plus.core.download.PersistedChapterDownloadTask { <fields>; }

-keepattributes Signature,*Annotation*

# The JVM download codec is retained in core for diagnostics and future download UI work.
# Android does not execute this path; R8 removes it from the current application surface.
-dontwarn java.awt.**
-dontwarn javax.imageio.**
-dontwarn com.twelvemonkeys.**
