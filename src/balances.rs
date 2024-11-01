use std::collections::BTreeMap;

pub struct Pallet {
    balances: BTreeMap<String, u128>,
}

impl Pallet {
    pub fn new() -> Self {
        Self{
            balances: BTreeMap::new()
        }
    }

    pub fn set_balance(&mut self, who: &String, amount: u128) {
        self.balances.insert(who.clone(), amount);
    }

    pub fn get_balance(&self, who: &String) -> u128 {
        *self.balances.get(who).unwrap_or(&0)
    }

    pub fn transfer(&mut self, caller: String, to: String, amount: u128)
        -> Result<(), &'static str> {
        let caller_balance: u128 = self.get_balance(&caller);
        let to_balance: u128 = self.get_balance(&to);

        let new_caller_balance: u128 = caller_balance
            .checked_sub(amount)
            .ok_or("Insufficient balance")?;

        let new_to_balance: u128 = to_balance
            .checked_add(amount)
            .ok_or("Overflow when adding to balance")?;
        self.set_balance(&caller, new_caller_balance);
        self.set_balance(&to, new_to_balance);

        Ok(())
    }
}

