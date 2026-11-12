# ScanON keeps no reflection-based serialization; org.json and Compose ship their own rules.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
# ViewModels are created reflectively by SavedStateViewModelFactory with (Application[, SavedStateHandle]).
-keepclassmembers class * extends androidx.lifecycle.ViewModel { <init>(...); }
