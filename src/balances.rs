use std::collections::BTreeMap;

pub struct Pallet {
    balances: BTreeMap<String, u128>,
}

impl Pallet {
    pub fn new() -> Self {
        Self {
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

#[cfg(test)]
mod tests {}
#[test]
fn init_balances() {
    let mut balances = Pallet::new();

    assert_eq!(balances.get_balance(&"alice".to_string()), 0);
    balances.set_balance(&"alice".to_string(), 100);
    assert_eq!(balances.get_balance(&"alice".to_string()), 100);
    assert_eq!(balances.get_balance(&"bob".to_string()), 0);
}

#[test]
fn transfer_balances() {
    let alice = "alice".to_string();
    let bob = "bob".to_string();

    let mut balances = Pallet::new();

    balances.set_balance(&"alice".to_string(), 100);
    let _ = balances.transfer(alice.clone(), bob.clone(), 90);

    assert_eq!(balances.get_balance(&alice), 10);
    assert_eq!(balances.get_balance(&bob), 90);
}
