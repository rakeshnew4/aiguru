from pydantic import BaseModel, EmailStr, Field
from typing import Optional


class CreateOrderRequest(BaseModel):
    user_id: str = Field(min_length=2)
    school_id: str = Field(min_length=1)
    plan_id: str = Field(min_length=1)
    amountInr: int = Field(gt=0)
    currency: str = Field(default="INR", min_length=3, max_length=3)
    prefill_name: Optional[str] = ""
    prefill_email: Optional[EmailStr] = None
    prefill_contact: Optional[str] = ""


class CreateOrderResponse(BaseModel):
    order_id: str
    amount: int
    currency: str
    key_id: str
    checkout_name: str
    checkout_description: str
    prefill_name: str
    prefill_email: str
    prefill_contact: str


class VerifyPaymentRequest(BaseModel):
    user_id: str = Field(min_length=2)
    school_id: str = Field(min_length=1)
    plan_id: str = Field(min_length=1)
    razorpay_payment_id: str = Field(min_length=5)
    razorpay_order_id: str = Field(min_length=5)
    razorpay_signature: str = Field(min_length=10)


class VerifyPaymentResponse(BaseModel):
    verified: bool
    message: str
