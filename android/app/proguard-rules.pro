# Keep this file intentionally small. Provider DTOs are plain Kotlin types and
# the initial native slice does not use reflection-based serialization.

# Google Play review-ktx references a build-time-only Play services annotation that no published
# artifact ships (AGP's generated missing_rules.txt suggests exactly this rule).
-dontwarn com.google.android.gms.common.annotation.NoNullnessRewrite
