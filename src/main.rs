use crate::balances::Pallet;

mod balances;

fn main() {
    println!("Hello, world!");
    //let mut pallet: Pallet = Pallet::new();
}

#[test]
fn init_balances(){
    let mut balances = Pallet::new();

    assert_eq!(balances.get_balance(&"alice".to_string()), 0);
    balances.set_balance(&"alice".to_string(), 100);
    assert_eq!(balances.get_balance(&"alice".to_string()), 100);
    assert_eq!(balances.get_balance(&"bob".to_string()), 0);
}

#[test]
fn transfer_balances(){
    let alice = "alice".to_string();
    let bob = "bob".to_string();

    let mut balances = Pallet::new();

    balances.set_balance(&"alice".to_string(), 100);
    let _ = balances.transfer(alice.clone(),bob.clone(),90);

    assert_eq!(balances.get_balance(&alice), 10);
    assert_eq!(balances.get_balance(&bob), 90);

}
