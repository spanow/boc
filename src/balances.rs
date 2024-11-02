use std::collections::BTreeMap;
use std::ops::AddAssign;
use num::{CheckedAdd, CheckedSub, One, Zero};

pub trait Config: crate::system::Config {
    type Balance: Zero + CheckedAdd + CheckedSub + Copy;
}

#[derive(Debug)]
pub struct Pallet<T: Config> {
    balances: BTreeMap<T::AccountId, T::Balance>,
}

impl<T: Config> Pallet<T> {
    pub fn new() -> Self {
        Self {
            balances: BTreeMap::new()
        }
    }

    pub fn set_balance(&mut self, who: &T::AccountId, amount: T::Balance) {
        self.balances.insert(who.clone(), amount);
    }

    pub fn get_balance(&self, who: &T::AccountId) -> T::Balance {
        *self.balances.get(who).unwrap_or(&T::Balance::zero())
    }

    pub fn transfer(&mut self, caller: T::AccountId, to: T::AccountId, amount: T::Balance)
                    -> Result<(), &'static str> {
        let caller_balance: T::Balance = self.get_balance(&caller);
        let to_balance: T::Balance = self.get_balance(&to);

        let new_caller_balance: T::Balance = caller_balance
            .checked_sub(&amount)
            .ok_or("Insufficient balance")?;

        let new_to_balance: T::Balance = to_balance
            .checked_add(&amount)
            .ok_or("Overflow when adding to balance")?;
        self.set_balance(&caller, new_caller_balance);
        self.set_balance(&to, new_to_balance);

        Ok(())
    }
}

#[cfg(test)]
mod tests {
    use crate::system;

    struct TestConfig;

    impl system::Config for TestConfig{
        type AccountId = String;
        type BlockNumber = u32;
        type Nonce = u32;
    }
    impl super::Config for TestConfig {
        type Balance = u32;
    }


    #[test]
    fn init_balances() {
        let mut balances: super::Pallet<TestConfig> = super::Pallet::new();

        assert_eq!(balances.get_balance(&"alice".to_string()), 0);
        balances.set_balance(&"alice".to_string(), 100);
        assert_eq!(balances.get_balance(&"alice".to_string()), 100);
        assert_eq!(balances.get_balance(&"bob".to_string()), 0);
    }

    #[test]
    fn transfer_balances() {
        let alice = "alice".to_string();
        let bob = "bob".to_string();

        let mut balances: super::Pallet<TestConfig> = super::Pallet::new();

        balances.set_balance(&"alice".to_string(), 100);
        let _ = balances.transfer(alice.clone(), bob.clone(), 90);

        assert_eq!(balances.get_balance(&alice), 10);
        assert_eq!(balances.get_balance(&bob), 90);
    }
}
