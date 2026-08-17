package uz.kassa.service;

/** Foydalanuvchiga ko'rsatiladigan biznes-xato (o'zbek tilida xabar bilan). */
public class BusinessException extends RuntimeException {
    public BusinessException(String message) { super(message); }
}
