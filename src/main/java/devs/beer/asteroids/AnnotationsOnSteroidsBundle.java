package devs.beer.asteroids;

import com.intellij.DynamicBundle;
import org.jetbrains.annotations.NonNls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.PropertyKey;

public class AnnotationsOnSteroidsBundle extends DynamicBundle {
    public static String message(@NotNull @PropertyKey(resourceBundle = BUNDLE) String key, Object @NotNull ... params) {
        return ourInstance.getMessage(key, params);
    }

    @NonNls private static final String BUNDLE = "messages.AnnotationsOnSteroidsBundle";
    private static final AnnotationsOnSteroidsBundle ourInstance = new AnnotationsOnSteroidsBundle();

    @NotNull
    private AnnotationsOnSteroidsBundle() {
        super(BUNDLE);
    }
}
