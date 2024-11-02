use std::collections::BTreeMap;
use std::ops::AddAssign;
use num::{One, Zero};

pub trait Config {
    type AccountId: Ord + Clone;
    type BlockNumber: Ord + Zero + One + AddAssign + Copy;
    type Nonce: Ord + Clone + Copy + Zero + One;
}

#[derive(Debug)]
pub struct Pallet<T: Config> {
    block_number: T::BlockNumber,
    nonce: BTreeMap<T::AccountId, T::Nonce>,
}

impl<T: Config> Pallet<T>
{
    pub fn new() -> Self {
        Self {
            block_number: T::BlockNumber::zero(),
            nonce: BTreeMap::new(),
        }
    }

    pub fn block_number(&self) -> T::BlockNumber {
        self.block_number
    }

    pub fn inc_block_number(&mut self) {
        self.block_number += T::BlockNumber::one();
    }

    pub fn inc_nonce(&mut self, who: &T::AccountId) {
        let nonce = *self.nonce.get(who).unwrap_or(&T::Nonce::zero());
        self.nonce.insert(who.clone(), nonce + T::Nonce::one());
    }

    pub fn get_nonce(&self, who: &T::AccountId) -> T::Nonce {
        *self.nonce.get(who).unwrap_or(&T::Nonce::zero())
    }
}

#[cfg(test)]
mod tests {
    struct TestConfig;

    impl super::Config for TestConfig {
        type AccountId = String;
        type BlockNumber = u32;
        type Nonce = u32;
    }
    use num::{One, Zero};
    #[test]
    fn init_system() {
        let system: super::Pallet<TestConfig> = super::Pallet::new();
        assert_eq!(system.block_number(), 0);
    }

    #[test]
    fn inc_block_number() {
        let mut system: super::Pallet<TestConfig> = super::Pallet::new();
        system.inc_block_number();
        assert_eq!(system.block_number(), 1);
    }

    #[test]
    fn inc_nonce() {
        let alice = String::from("alice");
        let mut system: super::Pallet<TestConfig> = super::Pallet::new();
        system.inc_nonce(&String::from(&alice.clone()));
        system.inc_nonce(&String::from(&alice.clone()));
        assert_eq!(system.get_nonce(&alice), 2);
    }
}