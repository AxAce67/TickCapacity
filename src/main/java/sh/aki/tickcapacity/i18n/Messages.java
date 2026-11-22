package sh.aki.tickcapacity.i18n;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;

import java.util.Locale;
import java.util.MissingResourceException;
import java.util.ResourceBundle;

/** MiniMessage templates from lang/messages_*.properties. Arguments are escaped, so values cannot inject tags. */
public final class Messages {

    private static final MiniMessage MM = MiniMessage.miniMessage();
    private final ResourceBundle bundle;
    private final ResourceBundle fallback;
    private final Locale locale;

    public Messages(String language) {
        this.locale = resolve(language);
        this.bundle = ResourceBundle.getBundle("lang.messages", locale, Messages.class.getClassLoader(),
                ResourceBundle.Control.getNoFallbackControl(ResourceBundle.Control.FORMAT_PROPERTIES));
        this.fallback = ResourceBundle.getBundle("lang.messages", Locale.ROOT, Messages.class.getClassLoader(),
                ResourceBundle.Control.getNoFallbackControl(ResourceBundle.Control.FORMAT_PROPERTIES));
    }

    private static Locale resolve(String language) {
        if (language == null || language.isBlank() || language.equalsIgnoreCase("auto")) {
            return Locale.getDefault();
        }
        return Locale.forLanguageTag(language.replace('_', '-'));
    }

    public Locale locale() {
        return locale;
    }

    /** Falls back to the key itself, so a missing translation is visible but harmless. */
    public String raw(String key) {
        try {
            return bundle.getString(key);
        } catch (MissingResourceException e) {
            try {
                return fallback.getString(key);
            } catch (MissingResourceException e2) {
                return key;
            }
        }
    }

    public boolean has(String key) {
        return bundle.containsKey(key) || fallback.containsKey(key);
    }

    public Component get(String key, Object... args) {
        return MM.deserialize(substitute(raw(key), true, args));
    }

    public String plain(String key, Object... args) {
        return substitute(MM.stripTags(raw(key)), false, args);
    }

    private static String substitute(String template, boolean escape, Object... args) {
        for (int i = 0; i < args.length; i++) {
            String value = String.valueOf(args[i]);
            if (escape) {
                // A straight quote would end a quoted tag argument such as a hover text.
                value = MM.escapeTags(value).replace('\'', '\u2019');
            }
            template = template.replace("{" + i + "}", value);
        }
        return template;
    }
}
