create function public.bootstrap_new_user()
returns trigger
language plpgsql
security definer
set search_path = ''
as $$
begin
  insert into public.profiles (id, display_name)
  values (new.id, coalesce(split_part(new.email, '@', 1), ''));

  insert into public.accounts (
    user_id, institution, external_key, name, type, role, purpose, mask,
    include_in_safe_to_spend, display_order
  )
  values
    (new.id, 'absa', 'CHEQ1607', 'Cheque', 'cheque', 'operational', 'Primary income and known expenses', '1607', true, 1),
    (new.id, 'absa', 'credit-card-1', 'Credit card 1', 'credit_card', 'operational', 'Groceries and utilities', 'Add mask', true, 2),
    (new.id, 'absa', 'SAVE2959', 'Savings account', 'savings', 'operational', 'Gas and phone', '2959', true, 3),
    (new.id, 'absa', 'credit-card-2', 'Credit card 2', 'credit_card', 'operational', 'Surplus spending', 'Add mask', true, 4),
    (new.id, 'absa', 'notice-savings-32', '32 day notice savings', 'savings', 'savings', 'Primary savings account', 'Add mask', false, 5),
    (new.id, 'absa', 'home-loan-1', 'Home loan 1', 'home_loan', 'liability', 'Mortgage for first property', 'Add mask', false, 6),
    (new.id, 'absa', 'home-loan-2', 'Home loan 2', 'home_loan', 'liability', 'Mortgage for second property', 'Add mask', false, 7),
    (new.id, 'absa', 'absa-rewards', 'Absa Rewards', 'rewards', 'rewards', 'Rewards balance', 'Rewards', false, 8);

  insert into public.categories (user_id, name, colour, icon, system_key, sort_order)
  values
    (new.id, 'Groceries', '#D9E8C4', 'shopping-basket', 'groceries', 1),
    (new.id, 'Utilities', '#C9DDF2', 'lightbulb', 'utilities', 2),
    (new.id, 'Gas', '#F4D7A1', 'flame', 'gas', 3),
    (new.id, 'Phone', '#D8D0EF', 'smartphone', 'phone', 4),
    (new.id, 'Savings', '#BFE3D0', 'piggy-bank', 'savings', 5),
    (new.id, 'Debt', '#F3C3C3', 'landmark', 'debt', 6),
    (new.id, 'Insurance', '#C9E3E8', 'shield', 'insurance', 7),
    (new.id, 'Home', '#E2D4C2', 'house', 'home', 8),
    (new.id, 'Transport', '#D4DEB8', 'car', 'transport', 9),
    (new.id, 'Education', '#D7C9EA', 'book-open', 'education', 10),
    (new.id, 'Entertainment', '#F0C9DC', 'sparkles', 'entertainment', 11),
    (new.id, 'Domestic help', '#E8D6BA', 'heart-handshake', 'domestic-help', 12),
    (new.id, 'Travel', '#BEDDE5', 'plane', 'travel', 13),
    (new.id, 'Other', '#E8E7E0', 'circle', 'other', 14);

  return new;
end;
$$;

revoke all on function public.bootstrap_new_user() from public, anon, authenticated;

create trigger bootstrap_budget_guard_user
after insert on auth.users
for each row execute function public.bootstrap_new_user();
