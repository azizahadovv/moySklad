package uz.kassa.bot;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;

/**
 * Rangli inline tugma — Bot API 9.4 «style» maydoni (primary — ko'k, success — yashil, danger — qizil).
 * telegrambots 6.9 kutubxonasi bu maydonni bilmaydi, lekin JSON'ni Jackson bilan runtime tipdan yig'adi,
 * shuning uchun subclass'dagi qo'shimcha maydon serverga yetib boradi (2026-09-11 jonli tekshirildi).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class StyledButton extends InlineKeyboardButton {

    public static final String PRIMARY = "primary", SUCCESS = "success", DANGER = "danger";

    @JsonProperty("style")
    private String style;

    public StyledButton() { super(); }

    public StyledButton(String text, String callbackData, String style) {
        super();
        setText(text);
        setCallbackData(callbackData);
        this.style = style;
    }

    public String getStyle() { return style; }
    public void setStyle(String style) { this.style = style; }
}
